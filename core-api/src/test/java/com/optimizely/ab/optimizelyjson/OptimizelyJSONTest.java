/**
 *
 *    Copyright 2020, Optimizely and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.optimizelyjson;

import com.google.gson.annotations.SerializedName;
import com.optimizely.ab.Optimizely;
import com.optimizely.ab.config.DatafileProjectConfig;
import com.optimizely.ab.config.PollingProjectConfigManagerTest;
import com.optimizely.ab.config.parser.ConfigParser;
import com.optimizely.ab.config.parser.DefaultConfigParser;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static com.optimizely.ab.config.DatafileProjectConfigTestUtils.validConfigJsonV4;
import static org.junit.Assert.*;

public class OptimizelyJSONTest {
    protected String orgJson;
    protected Map<String,Object> orgMap;

    @Before
    public void setUp() throws Exception {
        orgJson =
            "{                                          " +
            "   \"k1\": \"v1\",                         " +
            "   \"k2\": true,                           " +
            "   \"k3\": {                               " +
            "       \"kk1\": 1.0,                         " +
            "       \"kk2\": {                          " +
            "           \"kkk1\": true,                 " +
            "           \"kkk2\": 3.5,                  " +
            "           \"kkk3\": \"vvv3\",             " +
            "           \"kkk4\": [5.7, true, \"vvv4\"] " +
            "       }                                   " +
            "   }                                       " +
            "}                                          ";

        Map<String,Object> m3 = new HashMap<String,Object>();
        m3.put("kkk1", true);
        m3.put("kkk2", 3.5);
        m3.put("kkk3", "vvv3");
        m3.put("kkk4", new ArrayList(Arrays.asList(5.7, true, "vvv4")));

        Map<String,Object> m2 = new HashMap<String,Object>();
        m2.put("kk1", 1.0);
        m2.put("kk2", m3);

        Map<String,Object> m1 = new HashMap<String, Object>();
        m1.put("k1", "v1");
        m1.put("k2", true);
        m1.put("k3", m2);

        orgMap = m1;
    }




    protected String compact(String str) {
        return str.replaceAll("\\s", "");
    }
    protected ConfigParser getParser() { return DefaultConfigParser.getInstance(); }

    // Common tests for all parsers (GSON, Jackson, Json, JsonSimple)
    @Test
    public void testOptimizelyJSON()  {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());
        Map<String,Object> map = oj1.toMap();

        OptimizelyJSON oj2 = new OptimizelyJSON(map, getParser());
        String data = oj2.toString();

        assertEquals(compact(data), compact(orgJson));
    }

    @Test
    public void testToStringFromString() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());
        assertEquals(compact(oj1.toString()), compact(orgJson));
    }

    @Test
    public void testToStringFromMap() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgMap, getParser());
        assertEquals(compact(oj1.toString()), compact(orgJson));
    }

    @Test
    public void testToMapFromString() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());
        assertEquals(oj1.toMap(), orgMap);
    }

    @Test
    public void testToMapFromMap() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgMap, getParser());
        assertEquals(oj1.toMap(), orgMap);
    }

    @Test
    public void testGetValueNullKeyPath() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        MD1 md1 = oj1.getValue(null, MD1.class);
        assertNotNull(md1);
        assertEquals(md1.k1, "v1");
        assertEquals(md1.k2, true);
        assertEquals(md1.k3.kk1, 1.0, 0.01);
        assertEquals(md1.k3.kk2.kkk1, true);
        assertEquals((Double)md1.k3.kk2.kkk4[0], 5.7, 0.01);
        assertEquals(md1.k3.kk2.kkk4[2], "vvv4");

        // verify previous getValue does not destroy the data

        Boolean value = oj1.getValue("k3.kk2.kkk1", Boolean.class);
        assertEquals(value, true);
    }

    @Test
    public void testGetValueEmptyKeyPath() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        MD1 md1 = oj1.getValue("", MD1.class);
        assertEquals(md1.k1, "v1");
        assertEquals(md1.k2, true);
        assertEquals(md1.k3.kk1, 1.0, 0.01);
        assertEquals(md1.k3.kk2.kkk1, true);
        assertEquals((Double) md1.k3.kk2.kkk4[0], 5.7, 0.01);
        assertEquals(md1.k3.kk2.kkk4[2], "vvv4");
    }

    @Test
    public void testGetValueWithKeyPathToMapWithLevel1() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        MD2 md2 = oj1.getValue("k3", MD2.class);
        assertNotNull(md2);
        assertEquals(md2.kk1, 1.0, 0.01);
        assertEquals(md2.kk2.kkk1, true);
    }

    @Test
    public void testGetValueWithKeyPathToMapWithLevel2() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        MD3 md3 = oj1.getValue("k3.kk2", MD3.class);
        assertNotNull(md3);
        assertEquals(md3.kkk1, true);
    }

    @Test
    public void testGetValueWithKeyPathToBoolean() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        Boolean value = oj1.getValue("k3.kk2.kkk1", Boolean.class);
        assertNotNull(value);
        assertEquals(value, true);
    }

    @Test
    public void testGetValueWithKeyPathToDouble() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        Double value = oj1.getValue("k3.kk2.kkk2", Double.class);
        assertNotNull(value);
        assertEquals(value.doubleValue(), 3.5, 0.01);
    }

    @Test
    public void testGetValueWithKeyPathToString() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        String value = oj1.getValue("k3.kk2.kkk3", String.class);
        assertNotNull(value);
        assertEquals(value, "vvv3");
    }

    @Test
    public void testGetValueWithInvalidKeyPath() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        String value = oj1.getValue("k3..kkk3", String.class);
        assertNull(value);
    }

    @Test
    public void testGetValueWithInvalidKeyPath2() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        String value = oj1.getValue("k1.", String.class);
        assertNull(value);
    }

    @Test
    public void testGetValueWithInvalidKeyPath3() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        String value = oj1.getValue("x9", String.class);
        assertNull(value);
    }

    @Test
    public void testGetValueWithInvalidKeyPath4() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        String value = oj1.getValue("k3.x9", String.class);
        assertNull(value);
    }

    @Test
    public void testGetValueWithWrongType() {
        OptimizelyJSON oj1 = new OptimizelyJSON(orgJson, getParser());

        Integer value = oj1.getValue("k3.kk2.kkk3", Integer.class);
        assertNull(value);
    }

}

