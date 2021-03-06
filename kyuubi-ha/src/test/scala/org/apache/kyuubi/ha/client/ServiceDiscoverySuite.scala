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

package org.apache.kyuubi.ha.client

import java.io.{File, IOException}
import javax.security.auth.login.Configuration

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KerberizedTestHelper, KyuubiFunSuite}
import org.apache.kyuubi.KYUUBI_VERSION
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf._
import org.apache.kyuubi.ha.server.EmbeddedZkServer
import org.apache.kyuubi.service.ServiceState

class ServiceDiscoverySuite extends KyuubiFunSuite with KerberizedTestHelper {
  val zkServer = new EmbeddedZkServer()
  val conf: KyuubiConf = KyuubiConf()

  override def beforeAll(): Unit = {
    conf.set(KyuubiConf.EMBEDDED_ZK_PORT, 0)
    zkServer.initialize(conf)
    zkServer.start()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    conf.unset(KyuubiConf.SERVER_KEYTAB)
    conf.unset(KyuubiConf.SERVER_PRINCIPAL)
    conf.unset(HA_ZK_QUORUM)
    zkServer.stop()
    super.afterAll()
  }

  test("set up zookeeper auth") {
    tryWithSecurityEnabled {
      val keytab = File.createTempFile("kentyao", ".keytab")
      val principal = "kentyao/_HOST@apache.org"

      conf.set(KyuubiConf.SERVER_KEYTAB, keytab.getCanonicalPath)
      conf.set(KyuubiConf.SERVER_PRINCIPAL, principal)

      ServiceDiscovery.setUpZooKeeperAuth(conf)
      val configuration = Configuration.getConfiguration
      val entries = configuration.getAppConfigurationEntry("KyuubiZooKeeperClient")

      assert(entries.head.getLoginModuleName === "com.sun.security.auth.module.Krb5LoginModule")
      val options = entries.head.getOptions.asScala.toMap

      assert(options("principal") === "kentyao/localhost@apache.org")
      assert(options("useKeyTab").toString.toBoolean)

      conf.set(KyuubiConf.SERVER_KEYTAB, keytab.getName)
      val e = intercept[IOException](ServiceDiscovery.setUpZooKeeperAuth(conf))
      assert(e.getMessage === s"${KyuubiConf.SERVER_KEYTAB.key} does not exists")
    }
  }

  test("publish instance to embedded zookeeper server") {

    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)

    val namespace = "kyuubiserver"
    val znodeRoot = s"/$namespace"
    val instance = "kentyao.apache.org:10009"
    var deleted = false
    val postHook = new Thread {
      override def run(): Unit = deleted = true
    }
    val serviceDiscovery = new ServiceDiscovery(instance, namespace, postHook)
    val framework = ServiceDiscovery.newZookeeperClient(conf)
    try {
      serviceDiscovery.initialize(conf)
      serviceDiscovery.start()

      assert(framework.checkExists().forPath("/abc") === null)
      assert(framework.checkExists().forPath(znodeRoot) !== null)
      val children = framework.getChildren.forPath(znodeRoot).asScala
      assert(children.head ===
        s"serviceUri=$instance;version=$KYUUBI_VERSION;sequence=0000000000")

      children.foreach { child =>
        framework.delete().forPath(s"""$znodeRoot/$child""")
      }
      Thread.sleep(5000)
      assert(deleted, "Post hook called")
      assert(serviceDiscovery.getServiceState === ServiceState.STOPPED)
    } finally {
      serviceDiscovery.stop()
      framework.close()
    }
  }
}
