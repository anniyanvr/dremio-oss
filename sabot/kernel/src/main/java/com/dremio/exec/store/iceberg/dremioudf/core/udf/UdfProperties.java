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
package com.dremio.exec.store.iceberg.dremioudf.core.udf;

/** UDF properties that can be set during CREATE/REPLACE UDF or using updateProperties API. */
public class UdfProperties {
  public static final String VERSION_HISTORY_SIZE = "version.history.num-entries";
  public static final int VERSION_HISTORY_SIZE_DEFAULT = 10;

  public static final String METADATA_COMPRESSION = "write.metadata.compression-codec";
  public static final String METADATA_COMPRESSION_DEFAULT = "none";
  public static final String COMMENT = "comment";
  public static final String REPLACE_DROP_DIALECT_ALLOWED = "replace.drop-dialect.allowed";
  public static final boolean REPLACE_DROP_DIALECT_ALLOWED_DEFAULT = true;

  private UdfProperties() {}
}