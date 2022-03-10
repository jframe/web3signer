/*
 * Copyright 2020 ConsenSys AG.
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
package tech.pegasys.web3signer.slashingprotection.dao;

import static org.assertj.core.api.Assertions.assertThat;

import db.DatabaseSetupExtension;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseSetupExtension.class)
public class ValidatorsDaoTest {

  @Test
  public void retrievesSpecifiedValidatorsFromDb(final Handle handle) {
    insertValidator(handle, Bytes.of(100));
    insertValidator(handle, Bytes.of(101));
    insertValidator(handle, Bytes.of(102));

    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Validator> registeredValidators =
        validatorsDao.retrieveValidators(handle, List.of(Bytes.of(101), Bytes.of(102)));
    assertThat(registeredValidators).hasSize(2);
    assertThat(registeredValidators.get(0))
        .isEqualToComparingFieldByField(new Validator(2, Bytes.of(101)));
    assertThat(registeredValidators.get(1))
        .isEqualToComparingFieldByField(new Validator(3, Bytes.of(102)));
  }

  @Test
  public void storesValidatorsInDb(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Validator> validators =
        validatorsDao.registerValidators(handle, List.of(Bytes.of(101), Bytes.of(102)));

    assertThat(validators.size()).isEqualTo(2);
    assertThat(validators.get(0)).isEqualToComparingFieldByField(new Validator(1, Bytes.of(101)));
    assertThat(validators.get(1)).isEqualToComparingFieldByField(new Validator(2, Bytes.of(102)));
  }

  @Test
  public void storesUnregisteredValidatorsInDb(final Handle handle) {
    insertValidator(handle, Bytes.of(100));
    insertValidator(handle, Bytes.of(101));
    insertValidator(handle, Bytes.of(102));

    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Bytes> validators1 =
        List.of(Bytes.of(101), Bytes.of(102), Bytes.of(103), Bytes.of(104));
    final List<Validator> registeredValidators =
        validatorsDao.retrieveValidators(handle, validators1);
    final List<Bytes> validatorsMissingFromDb = new ArrayList<>(validators1);
    registeredValidators.forEach(v -> validatorsMissingFromDb.remove(v.getPublicKey()));
    validatorsDao.registerValidators(handle, validatorsMissingFromDb);

    final List<Validator> validators =
        handle
            .createQuery("SELECT * FROM validators ORDER BY ID")
            .mapToBean(Validator.class)
            .list();
    assertThat(validators.size()).isEqualTo(5);
    assertThat(validators.get(0)).isEqualToComparingFieldByField(new Validator(1, Bytes.of(100)));
    assertThat(validators.get(1)).isEqualToComparingFieldByField(new Validator(2, Bytes.of(101)));
    assertThat(validators.get(2)).isEqualToComparingFieldByField(new Validator(3, Bytes.of(102)));
    assertThat(validators.get(3)).isEqualToComparingFieldByField(new Validator(4, Bytes.of(103)));
    assertThat(validators.get(4)).isEqualToComparingFieldByField(new Validator(5, Bytes.of(104)));
  }

  @Test
  public void hasSignedReturnsFalseWhenNoSignedBlocksOrAttestations(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isFalse();
  }

  @Test
  public void hasSignedReturnsTrueWhenSignedBlock(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    insertBlock(handle, 1);
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isTrue();
  }

  @Test
  public void hasSignedReturnsTrueWhenSignedAttestation(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    insertAttestation(handle, 1);
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isTrue();
  }

  private void insertValidator(final Handle h, final Bytes publicKey) {
    insertValidator(h, publicKey, true);
  }

  private void insertValidator(final Handle h, final Bytes publicKey, final boolean enabled) {
    h.execute("INSERT INTO validators (public_key, enabled) VALUES (?, ?)", publicKey, enabled);
  }

  private void insertValidator(final Handle h, final int validatorId, final Bytes publicKey) {
    h.execute("INSERT INTO validators (id, public_key) VALUES (?, ?)", validatorId, publicKey);
  }

  private void insertBlock(final Handle handle, final int validatorId) {
    handle.execute(
        "INSERT INTO signed_blocks (validator_id, slot, signing_root) VALUES (?, ?, ?)",
        validatorId,
        2,
        Bytes.of(3));
  }

  private void insertAttestation(final Handle handle, final int validatorId) {
    handle.execute(
        "INSERT INTO signed_attestations "
            + "(validator_id, signing_root, source_epoch, target_epoch) "
            + "VALUES (?, ?, ?, ?)",
        validatorId,
        Bytes.of(2),
        UInt64.valueOf(3),
        UInt64.valueOf(4));
  }
}
