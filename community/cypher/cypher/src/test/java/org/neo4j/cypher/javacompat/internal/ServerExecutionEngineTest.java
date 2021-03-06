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
package org.neo4j.cypher.javacompat.internal;

import org.junit.Rule;
import org.junit.Test;
import org.neo4j.test.EmbeddedDatabaseRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerExecutionEngineTest
{
    @Rule
    public EmbeddedDatabaseRule rule = new EmbeddedDatabaseRule();

    @Test
    public void shouldDetectPeriodicCommitQueries() throws Exception
    {
        // GIVEN
        ServerExecutionEngine engine = new ServerExecutionEngine( rule.getGraphDatabaseService() );

        // WHEN
        boolean result = engine.isPeriodicCommitQuery("USING PERIODIC COMMIT CREATE ()");

        // THEN
        assertTrue( "Did not detect periodic commit query", result );
    }

    @Test
    public void shouldNotDetectNonPeriodicCommitQueriesAsPeriodicCommitQueries() throws Exception
    {
        // GIVEN
        ServerExecutionEngine engine = new ServerExecutionEngine( rule.getGraphDatabaseService() );

        // WHEN
        boolean result = engine.isPeriodicCommitQuery("CREATE ()");

        // THEN
        assertFalse( "Did detect non-periodic commit query as periodic commit query", result );
    }
}
