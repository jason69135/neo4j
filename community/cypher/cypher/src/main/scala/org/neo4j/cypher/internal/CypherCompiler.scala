/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal

import org.neo4j.cypher._
import CypherVersion._
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.kernel.{GraphDatabaseAPI, InternalAbstractGraphDatabase}
import org.neo4j.kernel.api.Statement
import org.neo4j.cypher.internal.compiler.v2_1.{CypherCompiler => CypherCompiler2_1}
import org.neo4j.cypher.internal.compiler.v2_0.{CypherCompiler => CypherCompiler2_0}
import org.neo4j.cypher.internal.compiler.v1_9.{CypherCompiler => CypherCompiler1_9}
import org.neo4j.cypher.internal.compiler.v2_1.executionplan.{ExecutionPlan => ExecutionPlan_v2_1}
import org.neo4j.cypher.internal.compiler.v2_0.executionplan.{ExecutionPlan => ExecutionPlan_v2_0}
import org.neo4j.cypher.internal.compiler.v1_9.executionplan.{ExecutionPlan => ExecutionPlan_v1_9}
import org.neo4j.cypher.internal.spi.v2_1.{TransactionBoundExecutionContext => QueryContext_v2_1}
import org.neo4j.cypher.internal.spi.v2_0.{TransactionBoundExecutionContext => QueryContext_v2_0}
import org.neo4j.cypher.internal.spi.v1_9.{GDSBackedQueryContext => QueryContext_v1_9}
import org.neo4j.cypher.internal.spi.v2_1.{TransactionBoundPlanContext => PlanContext_v2_1}
import org.neo4j.cypher.internal.spi.v2_0.{TransactionBoundPlanContext => PlanContext_v2_0}
import org.neo4j.cypher.internal.compiler.v2_1.spi.{ExceptionTranslatingQueryContext => ExceptionTranslatingQueryContext_v2_1}
import org.neo4j.cypher.internal.compiler.v2_0.spi.{ExceptionTranslatingQueryContext => ExceptionTranslatingQueryContext_v2_0}

object CypherCompiler {
  val DEFAULT_QUERY_CACHE_SIZE: Int = 128
  private val hasVersionDefined = """(?si)^\s*cypher\s*([^\s]+)\s*(.*)""".r
}

class CypherCompiler(graph: GraphDatabaseService, defaultVersion: CypherVersion = CypherVersion.vDefault) {

  def this(graph: GraphDatabaseService, versionName: String) = this(graph, CypherVersion(versionName))

  private val queryCache2_1 = new LRUCache[String, Object](getQueryCacheSize)
  private val queryCache2_0 = new LRUCache[String, Object](getQueryCacheSize)
  private val queryCache1_9 = new LRUCache[String, Object](getQueryCacheSize)

  private val compiler2_1 = new CypherCompiler2_1(graph, (q, f) => queryCache2_1.getOrElseUpdateByKey(q, f))
  private val compiler2_0 = new CypherCompiler2_0(graph, (q, f) => queryCache2_0.getOrElseUpdate(q, f))
  private val compiler1_9 = new CypherCompiler1_9(graph, (q, f) => queryCache1_9.getOrElseUpdate(q, f))

  @throws(classOf[SyntaxException])
  def prepare(query: String, context: GraphDatabaseService, statement: Statement): ExecutionPlan = {
    val (version, remainingQuery) = versionedQuery(query)

    version match {
      case CypherVersion.v2_1 =>
        val plan = compiler2_1.prepare(remainingQuery, new PlanContext_v2_1(statement, context))
        new ExecutionPlanWrapperForV2_1(plan)

      case CypherVersion.v2_0 =>
        val plan = compiler2_0.prepare(remainingQuery, new PlanContext_v2_0(statement, context))
        new ExecutionPlanWrapperForV2_0(plan)

      case CypherVersion.v1_9 =>
        val plan = compiler1_9.prepare(remainingQuery)
        new ExecutionPlanWrapperForV1_9(plan)
    }
  }

  @throws(classOf[SyntaxException])
  def isPeriodicCommit(query: String): Boolean = {
    val (version, remainingQuery) = versionedQuery(query)

    version match  {
      case CypherVersion.v2_1 => compiler2_1.isPeriodicCommit(remainingQuery)
      case _                  => false
    }
  }

  private def versionedQuery(query: String): (CypherVersion, String) = query match {
      case CypherCompiler.hasVersionDefined(versionName, tail) => (CypherVersion(versionName), tail)
      case _                                                   => (defaultVersion, query)
    }

  private def getQueryCacheSize : Int =
    optGraphAs[InternalAbstractGraphDatabase]
      .andThen(_.getConfig.get(GraphDatabaseSettings.query_cache_size))
      .andThen({
      case v: java.lang.Integer => v.intValue()
      case _                    => CypherCompiler.DEFAULT_QUERY_CACHE_SIZE
    })
      .applyOrElse(graph, (_: GraphDatabaseService) => CypherCompiler.DEFAULT_QUERY_CACHE_SIZE)

  private def optGraphAs[T <: GraphDatabaseService : Manifest]: PartialFunction[GraphDatabaseService, T] = {
    case (db: T) => db
  }
}

class ExecutionPlanWrapperForV2_1(inner: ExecutionPlan_v2_1) extends ExecutionPlan {

  private def queryContext(graph: GraphDatabaseAPI, txInfo: TransactionInfo) = {
    val ctx = new QueryContext_v2_1(graph, txInfo.tx, txInfo.isTopLevelTx, txInfo.statement)
    new ExceptionTranslatingQueryContext_v2_1(ctx)
  }

  def profile(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.profile(queryContext(graph, txInfo), params)

  def execute(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.execute(queryContext(graph, txInfo), params)
}

class ExecutionPlanWrapperForV2_0(inner: ExecutionPlan_v2_0) extends ExecutionPlan {

  private def queryContext(graph: GraphDatabaseAPI, txInfo: TransactionInfo) = {
    val ctx = new QueryContext_v2_0(graph, txInfo.tx, txInfo.isTopLevelTx, txInfo.statement)
    new ExceptionTranslatingQueryContext_v2_0(ctx)
  }

  def profile(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.profile(queryContext(graph, txInfo), params)

  def execute(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.execute(queryContext(graph, txInfo), params)
}

class ExecutionPlanWrapperForV1_9(inner: ExecutionPlan_v1_9) extends ExecutionPlan {

  private def queryContext(graph: GraphDatabaseAPI) =
    new QueryContext_v1_9(graph)

  def profile(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.profile(queryContext(graph), txInfo.tx, params)

  def execute(graph: GraphDatabaseAPI, txInfo: TransactionInfo, params: Map[String, Any]) =
    inner.execute(queryContext(graph), txInfo.tx, params)
}

