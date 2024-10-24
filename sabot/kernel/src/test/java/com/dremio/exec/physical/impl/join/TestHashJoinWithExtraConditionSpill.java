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
package com.dremio.exec.physical.impl.join;

import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.sabot.op.join.hash.HashJoinOperator;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/* All test cases from TestHashJoinWithExtraCondition will be executed in addition to separate test cases added below. */
public class TestHashJoinWithExtraConditionSpill extends TestHashJoinWithExtraCondition {

  @BeforeClass
  public static void beforeClass() throws Exception {
    setSystemOption(HashJoinOperator.ENABLE_SPILL, true);
    setSystemOption(PlannerSettings.USE_MAX_ROWCOUNT, false);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    resetSystemOption(HashJoinOperator.ENABLE_SPILL);
    resetSystemOption(PlannerSettings.USE_MAX_ROWCOUNT);
  }
}