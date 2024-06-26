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
package com.dremio.exec.store.parquet.columnreaders;

import com.dremio.BaseTestQuery;
import org.junit.Test;

public class TestDateReader extends BaseTestQuery {

  /** check if DateReader works well with dictionary encoding. */
  @Test
  public void testDictionary() throws Exception {
    // the file 'date_dictionary.parquet' contains two DATE columns, one optional and one required
    // and uses the PLAIN_DICTIONARY encoding

    // query parquet file. We shouldn't get any exception
    testNoResult("SELECT * FROM cp.\"parquet/date_dictionary.parquet\"");
  }

  /** check if DateReader works well with plain encoding. */
  @Test
  public void testNoDictionary() throws Exception {
    // the file 'date_dictionary.parquet' contains two DATE columns, one optional and one required
    // and uses the PLAIN encoding

    // query parquet file. We shouldn't get any exception
    testNoResult("SELECT * FROM cp.\"parquet/date_nodictionary.parquet\"");
  }
}
