/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.work.batch;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.base.Receiver;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.record.RawFragmentBatch;
import org.apache.drill.exec.rpc.RemoteConnection;

import com.google.common.base.Preconditions;

public abstract class AbstractDataCollector implements DataCollector, ReadController{

  private final List<DrillbitEndpoint> incoming;
  private final int oppositeMajorFragmentId;
  private final AtomicIntegerArray remainders;
  private final AtomicInteger remainingRequired;
  protected final RawBatchBuffer[] buffers;
  private final AtomicReferenceArray<RemoteConnection> connections;
  private final AtomicInteger parentAccounter;
  private final AtomicInteger finishedStreams = new AtomicInteger();

  public AbstractDataCollector(AtomicInteger parentAccounter, Receiver receiver, int minInputsRequired, FragmentContext context) {
    Preconditions.checkArgument(minInputsRequired > 0);
    Preconditions.checkNotNull(receiver);
    Preconditions.checkNotNull(parentAccounter);

    this.parentAccounter = parentAccounter;
    this.incoming = receiver.getProvidingEndpoints();
    this.connections = new AtomicReferenceArray<>(incoming.size());
    this.remainders = new AtomicIntegerArray(incoming.size());
    this.oppositeMajorFragmentId = receiver.getOppositeMajorFragmentId();
    this.buffers = new RawBatchBuffer[minInputsRequired];
    try {
      String bufferClassName = context.getConfig().getString(ExecConstants.INCOMING_BUFFER_IMPL);
      Constructor<?> bufferConstructor = Class.forName(bufferClassName).getConstructor(FragmentContext.class, ReadController.class, int.class);
      for(int i = 0; i < buffers.length; i++) {
          buffers[i] = (RawBatchBuffer) bufferConstructor.newInstance(context, this, incoming.size());
      }
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
            NoSuchMethodException | ClassNotFoundException e) {
      context.fail(e);
    }
    if (receiver.supportsOutOfOrderExchange()) {
      this.remainingRequired = new AtomicInteger(1);
    } else {
      this.remainingRequired = new AtomicInteger(minInputsRequired);
    }
  }

  public int getOppositeMajorFragmentId() {
    return oppositeMajorFragmentId;
  }

  public RawBatchBuffer[] getBuffers(){
    return buffers;
  }


  public void setAutoRead(boolean enabled){
    for(int i = 0; i < connections.length(); i++){
      setAutoRead(i, enabled);
    }
  }

  public void setAutoRead(int minorFragmentId, boolean enabled){
    RemoteConnection c = connections.get(minorFragmentId);
    if(c != null) c.setAutoRead(enabled);
  }

  public abstract void streamFinished(int minorFragmentId);

  public boolean batchArrived(int minorFragmentId, RawFragmentBatch batch)  throws IOException {

    // if we received an out of memory, add an item to all the buffer queues.
    if (batch.getHeader().getIsOutOfMemory()) {
      for (RawBatchBuffer buffer : buffers) {
        buffer.enqueue(batch);
      }
    }

    // add the connection to the connection list if this is the first time we're seeing it.
    // TODO: move this to a better location (e.g. connection setup).
    if(connections.compareAndSet(minorFragmentId, null, batch.getConnection()));

    // check to see if we have enough fragments reporting to proceed.
    boolean decremented = false;
    if (remainders.compareAndSet(minorFragmentId, 0, 1)) {
      int rem = remainingRequired.decrementAndGet();
      if (rem == 0) {
        parentAccounter.decrementAndGet();
        decremented = true;
      }
    }

    // mark stream finished if we got the last batch.
    if(batch.getHeader().getIsLastBatch()){
      streamFinished(minorFragmentId);
    }


    getBuffer(minorFragmentId).enqueue(batch);
    return decremented;
  }


  @Override
  public int getTotalIncomingFragments() {
    return incoming.size();
  }

  protected abstract RawBatchBuffer getBuffer(int minorFragmentId);

  @Override
  public void close() {
    for (int i = 0; i < connections.length(); i++) {
      if (connections.get(i) != null) {
        connections.get(i).close();
      };
    }
  }

}