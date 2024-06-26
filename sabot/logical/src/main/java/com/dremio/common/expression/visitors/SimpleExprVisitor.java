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
package com.dremio.common.expression.visitors;

import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.FunctionHolderExpression;
import com.dremio.common.expression.IfExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.ValueExpressions.BooleanExpression;
import com.dremio.common.expression.ValueExpressions.DateExpression;
import com.dremio.common.expression.ValueExpressions.DecimalExpression;
import com.dremio.common.expression.ValueExpressions.DoubleExpression;
import com.dremio.common.expression.ValueExpressions.FloatExpression;
import com.dremio.common.expression.ValueExpressions.IntExpression;
import com.dremio.common.expression.ValueExpressions.IntervalDayExpression;
import com.dremio.common.expression.ValueExpressions.IntervalYearExpression;
import com.dremio.common.expression.ValueExpressions.LongExpression;
import com.dremio.common.expression.ValueExpressions.QuotedString;
import com.dremio.common.expression.ValueExpressions.TimeExpression;
import com.dremio.common.expression.ValueExpressions.TimeStampExpression;

public abstract class SimpleExprVisitor<T> implements ExprVisitor<T, Void, RuntimeException> {

  @Override
  public T visitFunctionCall(FunctionCall call, Void value) throws RuntimeException {
    return visitFunctionCall(call);
  }

  @Override
  public T visitFunctionHolderExpression(FunctionHolderExpression holder, Void value)
      throws RuntimeException {
    return visitFunctionHolderExpression(holder);
  }

  @Override
  public T visitIfExpression(IfExpression ifExpr, Void value) throws RuntimeException {
    return visitIfExpression(ifExpr);
  }

  @Override
  public T visitSchemaPath(SchemaPath path, Void value) throws RuntimeException {
    return visitSchemaPath(path);
  }

  @Override
  public T visitIntConstant(IntExpression intExpr, Void value) throws RuntimeException {
    return visitIntConstant(intExpr);
  }

  @Override
  public T visitFloatConstant(FloatExpression fExpr, Void value) throws RuntimeException {
    return visitFloatConstant(fExpr);
  }

  @Override
  public T visitLongConstant(LongExpression intExpr, Void value) throws RuntimeException {
    return visitLongConstant(intExpr);
  }

  @Override
  public T visitDateConstant(DateExpression intExpr, Void value) throws RuntimeException {
    return visitDateConstant(intExpr);
  }

  @Override
  public T visitTimeConstant(TimeExpression intExpr, Void value) throws RuntimeException {
    return visitTimeConstant(intExpr);
  }

  @Override
  public T visitIntervalYearConstant(IntervalYearExpression intExpr, Void value)
      throws RuntimeException {
    return visitIntervalYearConstant(intExpr);
  }

  @Override
  public T visitIntervalDayConstant(IntervalDayExpression intExpr, Void value)
      throws RuntimeException {
    return visitIntervalDayConstant(intExpr);
  }

  @Override
  public T visitTimeStampConstant(TimeStampExpression intExpr, Void value) throws RuntimeException {
    return visitTimeStampConstant(intExpr);
  }

  @Override
  public T visitDecimalConstant(DecimalExpression decExpr, Void value) throws RuntimeException {
    return visitDecimalConstant(decExpr);
  }

  @Override
  public T visitDoubleConstant(DoubleExpression dExpr, Void value) throws RuntimeException {
    return visitDoubleConstant(dExpr);
  }

  @Override
  public T visitBooleanConstant(BooleanExpression e, Void value) throws RuntimeException {
    return visitBooleanConstant(e);
  }

  @Override
  public T visitQuotedStringConstant(QuotedString e, Void value) throws RuntimeException {
    return visitQuotedStringConstant(e);
  }

  public abstract T visitFunctionCall(FunctionCall call);

  public abstract T visitFunctionHolderExpression(FunctionHolderExpression call);

  public abstract T visitIfExpression(IfExpression ifExpr);

  public abstract T visitSchemaPath(SchemaPath path);

  public abstract T visitIntConstant(IntExpression intExpr);

  public abstract T visitFloatConstant(FloatExpression fExpr);

  public abstract T visitLongConstant(LongExpression intExpr);

  public abstract T visitDateConstant(DateExpression intExpr);

  public abstract T visitTimeConstant(TimeExpression intExpr);

  public abstract T visitIntervalYearConstant(IntervalYearExpression intExpr);

  public abstract T visitIntervalDayConstant(IntervalDayExpression intExpr);

  public abstract T visitTimeStampConstant(TimeStampExpression intExpr);

  public abstract T visitDecimalConstant(DecimalExpression intExpr);

  public abstract T visitDoubleConstant(DoubleExpression dExpr);

  public abstract T visitBooleanConstant(BooleanExpression e);

  public abstract T visitQuotedStringConstant(QuotedString e);
}
