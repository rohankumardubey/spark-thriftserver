/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.thriftserver.auth;

import org.apache.hive.service.rpc.thrift.TCLIService;
import org.apache.hive.service.rpc.thrift.TCLIService.Iface;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.transport.TTransport;

import org.apache.spark.sql.thriftserver.auth.thrift.HadoopThriftAuthBridge.Server;
import org.apache.spark.sql.thriftserver.cli.thrift.ThriftCLIService;

public final class KerberosSaslHelper {

  public static TProcessorFactory getKerberosProcessorFactory(
      Server saslServer,
      ThriftCLIService service) {
    return new CLIServiceProcessorFactory(saslServer, service);
  }

  private KerberosSaslHelper() {
    throw new UnsupportedOperationException("Can't initialize class");
  }

  private static class CLIServiceProcessorFactory extends TProcessorFactory {

    private final ThriftCLIService service;
    private final Server saslServer;

    CLIServiceProcessorFactory(Server saslServer, ThriftCLIService service) {
      super(null);
      this.service = service;
      this.saslServer = saslServer;
    }

    @Override
    public TProcessor getProcessor(TTransport trans) {
      TProcessor sqlProcessor = new TCLIService.Processor<Iface>(service);
      return saslServer.wrapNonAssumingProcessor(sqlProcessor);
    }
  }
}
