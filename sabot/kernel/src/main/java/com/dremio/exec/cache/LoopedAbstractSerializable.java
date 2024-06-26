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
package com.dremio.exec.cache;

import com.dremio.common.util.DataInputInputStream;
import com.dremio.common.util.DataOutputOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;

/**
 * Helper class that holds the basic functionality to interchangeably use the different Dremio
 * serializable interfaces. This is package private as users should utilize either
 * AbstractDataSerializable or AbstractStreamSerializable instead to avoid infinite loops.
 */
abstract class LoopedAbstractSerializable implements Serializable {

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    writeToStream(baos);
    final byte[] ba = baos.toByteArray();
    out.writeInt(ba.length);
    out.write(ba);
  }

  @Override
  public void read(DataInput input) throws IOException {
    readFromStream(DataInputInputStream.constructInputStream(input));
  }

  @Override
  public void write(DataOutput output) throws IOException {
    writeToStream(DataOutputOutputStream.constructOutputStream(output));
  }

  @Override
  public void readFromStream(InputStream input) throws IOException {
    read(new DataInputStream(input));
  }

  @Override
  public void writeToStream(OutputStream output) throws IOException {
    write(new DataOutputStream(output));
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    final int len = in.readInt();
    final byte[] bytes = new byte[len];
    in.readFully(bytes);
    readFromStream(new ByteArrayInputStream(bytes));
  }
}
