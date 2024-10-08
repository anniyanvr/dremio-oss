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
package com.dremio.common.logical.data;

import com.dremio.common.JSONOptions;
import com.dremio.common.logical.data.visitors.LogicalVisitor;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.Iterators;
import java.util.Iterator;

@JsonTypeName("store")
public class Store extends SinkOperator {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Store.class);

  private final String storageEngine;
  private final JSONOptions target;

  @JsonCreator
  public Store(
      @JsonProperty("storageengine") String storageEngine,
      @JsonProperty("target") JSONOptions target) {
    super();
    this.storageEngine = storageEngine;
    this.target = target;
  }

  public String getStorageEngine() {
    return storageEngine;
  }

  public JSONOptions getTarget() {
    return target;
  }

  @Override
  public <T, X, E extends Throwable> T accept(LogicalVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitStore(this, value);
  }

  @Override
  public Iterator<LogicalOperator> iterator() {
    return Iterators.singletonIterator(getInput());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends AbstractSingleBuilder<Store, Builder> {
    private String storageEngine;
    private JSONOptions target;

    public Builder storageEngine(String storageEngine) {
      this.storageEngine = storageEngine;
      return this;
    }

    public Builder target(JSONOptions target) {
      this.target = target;
      return this;
    }

    @Override
    public Store internalBuild() {
      return new Store(storageEngine, target);
    }
  }
}
