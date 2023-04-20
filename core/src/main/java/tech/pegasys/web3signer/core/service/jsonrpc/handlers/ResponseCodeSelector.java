/*
 * Copyright 2021 ConsenSys AG.
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
package tech.pegasys.web3signer.core.service.jsonrpc.handlers;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import tech.pegasys.web3signer.core.service.jsonrpc.exceptions.JsonRpcException;

public class ResponseCodeSelector {
  public static int jsonRPCErrorCode(final JsonRpcException e) {
    switch (e.getJsonRpcError()) {
      case INVALID_REQUEST:
      case INVALID_PARAMS:
      case PARSE_ERROR:
        return BAD_REQUEST.code();
      default:
        return OK.code();
    }
  }
}
