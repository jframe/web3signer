/*
 * Copyright 2023 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.signing.secp256k1.aws;

import static com.google.common.base.Preconditions.checkArgument;

import tech.pegasys.web3signer.keystorage.common.MappedResults;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeyListEntry;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.Tag;

/**
 * Wraps KmsClient to allow the same instance to be cached and re-used. It exposes the methods that
 * our code use. Since AwsKmsClient is meant to live for the duration of web3signer life, we have
 * not implemented close method.
 */
public class AwsKmsClient {
  private static final Logger LOG = LogManager.getLogger();
  private static final Provider BC_PROVIDER = new BouncyCastleProvider();
  private final KmsClient kmsClient;

  public AwsKmsClient(final KmsClient kmsClient) {
    this.kmsClient = kmsClient;
  }

  public ECPublicKey getECPublicKey(final String kmsKeyId) {
    // kmsClient can be null/closed if close method has been called.
    checkArgument(kmsClient != null, "KmsClient is not initialized");

    final GetPublicKeyRequest getPublicKeyRequest =
        GetPublicKeyRequest.builder().keyId(kmsKeyId).build();
    final GetPublicKeyResponse publicKeyResponse = kmsClient.getPublicKey(getPublicKeyRequest);
    final KeySpec keySpec = publicKeyResponse.keySpec();
    if (keySpec != KeySpec.ECC_SECG_P256_K1) {
      throw new RuntimeException("Unsupported key spec from AWS KMS: " + keySpec.toString());
    }

    final X509EncodedKeySpec encodedKeySpec =
        new X509EncodedKeySpec(publicKeyResponse.publicKey().asByteArray());
    try {
      final KeyFactory keyFactory = KeyFactory.getInstance("EC", BC_PROVIDER);
      return (ECPublicKey) keyFactory.generatePublic(encodedKeySpec);
    } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
      // very unlikely to happen unless BouncyCastle suddenly stop supporting EC curve based keys
      throw new RuntimeException(e);
    }
  }

  public byte[] sign(final String kmsKeyId, final byte[] data) {
    final SignRequest signRequest =
        SignRequest.builder()
            .keyId(kmsKeyId)
            .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
            .messageType(MessageType.DIGEST)
            .message(SdkBytes.fromByteArray(data))
            .build();

    return kmsClient.sign(signRequest).signature().asByteArray();
  }

  public <R> MappedResults<R> mapKeyList(
      final Function<KeyListEntry, R> mapper,
      final Collection<String> namePrefixes,
      final Collection<String> tagKeys,
      final Collection<String> tagValues) {
    final Set<R> result = ConcurrentHashMap.newKeySet();
    final AtomicInteger errorCount = new AtomicInteger(0);

    try {
      kmsClient
          .listKeysPaginator()
          .iterator()
          .forEachRemaining(
              listKeysResponse ->
                  listKeysResponse.keys().parallelStream()
                      .filter(
                          keyListEntry ->
                              keyListPredicate(keyListEntry.keyId(), tagKeys, tagValues))
                      .forEach(
                          keyListEntry -> {
                            try {
                              final R value = mapper.apply(keyListEntry);
                              result.add(value);
                            } catch (final Exception e) {
                              LOG.warn(
                                  "Failed to map keyListEntry '{}' to requested object type.",
                                  keyListEntry.keyId(),
                                  e);
                              errorCount.incrementAndGet();
                            }
                          }));
    } catch (Exception e) {
      LOG.error("Unexpected error during Aws mapKeyList", e);
      errorCount.incrementAndGet();
    }

    return MappedResults.newInstance(result, errorCount.intValue());
  }

  private boolean keyListPredicate(
      final String keyId, final Collection<String> tagKeys, final Collection<String> tagValues) {
    if (tagKeys.isEmpty() && tagValues.isEmpty())
      return true; // we don't want to filter if user-supplied tags map is empty

    final List<Tag> kmsTags =
        kmsClient.listResourceTags(ListResourceTagsRequest.builder().keyId(keyId).build()).tags();
    return matchesTag(kmsTags, tagKeys, Tag::tagKey)
        && matchesTag(kmsTags, tagValues, Tag::tagValue);
  }

  private boolean matchesTag(
      final List<Tag> kmsTags,
      final Collection<String> tags,
      final Function<Tag, String> tagProperty) {
    return tags.isEmpty()
        || kmsTags.stream().allMatch(tag -> tags.contains(tagProperty.apply(tag)));
  }

  @VisibleForTesting
  public String createKey(CreateKeyRequest createKeyRequest) {
    return kmsClient.createKey(createKeyRequest).keyMetadata().keyId();
  }

  @VisibleForTesting
  public void scheduleKeyDeletion(ScheduleKeyDeletionRequest deletionRequest) {
    kmsClient.scheduleKeyDeletion(deletionRequest);
  }
}
