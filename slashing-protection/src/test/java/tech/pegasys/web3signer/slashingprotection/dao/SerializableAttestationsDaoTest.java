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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.SerializableTransactionRunner;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import org.jdbi.v3.testing.JdbiRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SerializableAttestationsDaoTest {

  @Rule public JdbiRule postgres = JdbiRule.embeddedPostgres();

  private Jdbi db;

  @Before
  public void setup() {
    db = postgres.getJdbi();
    db.setTransactionHandler(new SerializableTransactionRunner());
  }

  @Test
  public void serializableTest() throws ExecutionException, InterruptedException {
    BiConsumer<Handle, Integer> insert =
        (h, i) -> h.execute("INSERT INTO ints(value) VALUES(?)", i);

    db.useHandle(h -> h.execute("CREATE TABLE ints (value INTEGER)"));

    // Run the following twice in parallel, and synchronize
    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch latch = new CountDownLatch(2);
    Callable<Boolean> selectAndInsert =
        () ->
            db.inTransaction(
                TransactionIsolationLevel.SERIALIZABLE,
                h -> {
                  final Optional<Integer> value =
                      h.select("SELECT value FROM ints where value = ?")
                          .bind(0, 1)
                          .mapTo(int.class)
                          .findFirst();
                  // Both read initial state of table

                  // First time through, make sure neither transaction writes until both have read
                  latch.countDown();
                  latch.await();

                  // Now do the write.
                  if (value.isEmpty()) {
                    insert.accept(h, 1);
                    return true;
                  }
                  return false;
                });

    Future<Boolean> result1 = executor.submit(selectAndInsert);
    Future<Boolean> result2 = executor.submit(selectAndInsert);
    final boolean value1 = result1.get();
    final boolean value2 = result2.get();

    final List<Integer> values =
        db.withHandle(h -> h.select("SELECT * from ints").mapTo(int.class).list());
    assertThat(values).hasSize(1);

    assertThat(value1).isNotEqualTo(value2);
    assertThat(value1 || value2).isTrue();

    executor.shutdown();
  }
}
