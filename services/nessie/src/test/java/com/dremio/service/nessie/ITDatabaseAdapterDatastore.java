/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.nessie;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.projectnessie.versioned.persist.tests.AbstractDatabaseAdapterTest;
import org.projectnessie.versioned.persist.tests.extension.NessieDbAdapterName;
import org.projectnessie.versioned.persist.tests.extension.NessieExternalDatabase;

@NessieDbAdapterName(DatastoreDatabaseAdapterFactory.NAME)
@NessieExternalDatabase(DatastoreTestConnectionProviderSource.class)
class ITDatabaseAdapterDatastore extends AbstractDatabaseAdapterTest {

  @Nested
  @Disabled
  public class RefLog {
    // Ignore this nested suite of tests - reflog is not supported by DatastoreDatabaseAdapter
  }
}
