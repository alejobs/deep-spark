/*
 * Copyright 2014, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.deep.aerospike.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stratio.deep.commons.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aerospike.client.Record;
import com.aerospike.hadoop.mapreduce.AerospikeKey;
import com.aerospike.hadoop.mapreduce.AerospikeRecord;
import com.stratio.deep.aerospike.config.AerospikeDeepJobConfig;
import com.stratio.deep.commons.entity.Cell;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.commons.exception.DeepGenericException;
import com.stratio.deep.commons.utils.AnnotationUtils;
import com.stratio.deep.commons.utils.Utils;

import scala.Tuple2;

/**
 * Several utilities to work used in the Spark <=> Aerospike integration.
 */
final public class UtilAerospike {

    private static final Logger LOG = LoggerFactory.getLogger(UtilAerospike.class);

    /**
     * Private default constructor.
     */
    private UtilAerospike() {
        throw new UnsupportedOperationException();
    }

    /**
     * Converts from AerospikeRecord to an entity class with deep's anotations.
     *
     * @param classEntity     the entity name.
     * @param aerospikeRecord the instance of the AerospikeRecord to convert.
     * @param aerospikeConfig Aerospike configuration object.
     * @param <T>             return type.
     * @return the provided aerospikeRecord converted to an instance of T.
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws java.lang.reflect.InvocationTargetException
     */
    public static <T> T getObjectFromAerospikeRecord(Class<T> classEntity, AerospikeRecord aerospikeRecord,
                                                     AerospikeDeepJobConfig aerospikeConfig)
            throws IllegalAccessException, InstantiationException,
            InvocationTargetException {
        Tuple2<String, Object> equalsFilter = aerospikeConfig.getEqualsFilter();
        String equalsFilterBin = equalsFilter != null ? equalsFilter._1() : null;
        Object equalsFilterValue = equalsFilter != null ? equalsFilter._2() : null;
        Map<String, Object> bins = aerospikeRecord.bins;
        T t = classEntity.newInstance();
        if (equalsFilter == null || checkEqualityFilter(bins, equalsFilterBin, equalsFilterValue)) {
            Field[] fields = AnnotationUtils.filterDeepFields(classEntity);
            Object insert = null;
            List<String> inputColumns = null;

            if (aerospikeConfig.getInputColumns() != null) {
                inputColumns = Arrays.asList(aerospikeConfig.getInputColumns());
            }

            for (Field field : fields) {

                if (inputColumns != null && !inputColumns.contains(AnnotationUtils.deepFieldName(field))) {
                    continue;
                }
                Object currentBin = null;
                Method method = null;
                Class<?> classField = field.getType();
                try {
                    method = Utils.findSetter(field.getName(), classEntity, field.getType());

                    currentBin = bins.get(AnnotationUtils.deepFieldName(field));

                    if (currentBin != null) {
                        if (currentBin instanceof Integer && classField.equals(Long.class)) {
                            currentBin = new Long((Integer) currentBin);
                        }
                        if (currentBin instanceof String || currentBin instanceof Integer
                                || currentBin instanceof Long) {
                            insert = currentBin;
                        } else {
                            throw new DeepGenericException("Data type [" + classField.toString()
                                    + "] not supported in Aerospike entity extractor (only Strings and Integers)");
                        }
                        method.invoke(t, insert);
                    }
                } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                    LOG.error("impossible to create a java object from Bin:" + field.getName() + " and type:"
                            + field.getType() + " and value:" + t + "; recordReceived:" + currentBin);

                    method.invoke(t, Utils.castNumberType(insert, classField));
                }
            }
        }
        return t;
    }

    /**
     * Converts from an entity class with deep's anotations to AerospikeRecord.
     *
     * @param t   an instance of an object of type T to convert to AerospikeRecord.
     * @param <T> the type of the object to convert.
     * @return A pair with the Record key and the Record itself.
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws InvocationTargetException
     */
    public static <T> Pair<Object, AerospikeRecord> getAerospikeRecordFromObject(T t) throws IllegalAccessException, InstantiationException,
            InvocationTargetException {
        Field[] fields = AnnotationUtils.filterDeepFields(t.getClass());
        Pair<Field[], Field[]> keysAndFields = AnnotationUtils.filterKeyFields(t.getClass());
        Field[] keys = keysAndFields.left;

        Object key;
        Map<String, Object> bins = new HashMap<>();

        if(keys.length == 0) {
            throw new InvocationTargetException(new Exception("One key field must be defined."));
        } else if(keys.length > 1) {
            throw new InvocationTargetException(new Exception("Aerospike only supports one key field"));
        } else {
            Field keyField = keys[0];
            Method method = Utils.findGetter(keyField.getName(), t.getClass());
            key = method.invoke(t);
        }

        for (Field field : fields) {
            Method method = Utils.findGetter(field.getName(), t.getClass());
            Object object = method.invoke(t);
            if (object != null) {
                bins.put(AnnotationUtils.deepFieldName(field), object);
            }
        }
        Record record = new Record(bins, 0, 0);
        AerospikeRecord aerospikeRecord = new AerospikeRecord(record);
        Pair<Object, AerospikeRecord> result = Pair.create(key, aerospikeRecord);
        return result;
    }

    /**
     * Converts from AerospikeRecord to cell class with deep's anotations.
     *
     * @param aerospikeRecord
     * @param key
     * @param aerospikeConfig
     * @return
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws InvocationTargetException
     */
    public static Cells getCellFromAerospikeRecord(AerospikeKey key, AerospikeRecord aerospikeRecord,
                                                   AerospikeDeepJobConfig aerospikeConfig) throws IllegalAccessException,
            InstantiationException, InvocationTargetException {

        String namespace = aerospikeConfig.getNamespace() + "." + aerospikeConfig.getSet();
        String setName = aerospikeConfig.getSet();
        String[] inputColumns = aerospikeConfig.getInputColumns();
        Tuple2<String, Object> equalsFilter = aerospikeConfig.getEqualsFilter();
        String equalsFilterBin = equalsFilter != null ? equalsFilter._1() : null;
        Object equalsFilterValue = equalsFilter != null ? equalsFilter._2() : null;

        Cells cells = namespace != null ? new Cells(namespace) : new Cells();

        Map<String, Object> map = aerospikeRecord.bins;
        if (inputColumns != null) {
            if (equalsFilter == null || checkEqualityFilter(map, equalsFilterBin, equalsFilterValue)) {
                for (int i = 0; i < inputColumns.length; i++) {
                    String binName = inputColumns[i];
                    if (map.containsKey(binName)) {
                        Cell cell = Cell.create(binName, map.get(binName));
                        if(i == 0) {
                            cell.setIsClusterKey(true);
                            cell.setIsKey(true);
                        }
                        cells.add(namespace, cell);
                    } else {
                        throw new InvocationTargetException(new Exception("There is no [" + binName
                                + "] on aerospike [" + namespace + "." + setName + "] set"));
                    }
                }
            }
        } else {
            if (equalsFilter == null || checkEqualityFilter(map, equalsFilterBin, equalsFilterValue)) {
                int index = 0;
                for (Map.Entry<String, Object> bin : map.entrySet()) {
                    Cell cell = Cell.create(bin.getKey(), bin.getValue());
                    if(index == 0) {
                        cell.setIsClusterKey(true);
                        cell.setIsKey(true);
                    }
                    cells.add(namespace, cell);
                    index ++;
                }
            }
        }

        return cells;
    }

    private static boolean checkEqualityFilter(Map<String, Object> bins, String binName, Object expectedBinValue) {
        Object currentBinValue = bins.get(binName);
        if (currentBinValue instanceof Integer && expectedBinValue instanceof Long) {
            currentBinValue = new Long((Integer) currentBinValue);
        }
        return bins.containsKey(binName) && currentBinValue.equals(expectedBinValue);
    }

    /**
     * Converts from and entity class with deep's anotations to AerospikeRecord.
     *
     * @param cells
     * @return
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws InvocationTargetException
     */
    public static Pair<Object, AerospikeRecord> getAerospikeRecordFromCell(Cells cells) throws IllegalAccessException, InstantiationException,
            InvocationTargetException {
        Map<String, Object> bins = new HashMap<>();
        Object key = null;
        for (Cell cell : cells.getCells()) {
            if(key == null) {
                if(cell.isKey()) {
                    key = cell.getValue();
                }
            } else {
                if(cell.isKey()) {
                    throw new InvocationTargetException(new Exception("Aerospike records must have only one key"));
                }
            }
            bins.put(cell.getCellName(), cell.getValue());
        }
        if(key == null) {
            throw new InvocationTargetException(new Exception("Aerospike records must have one primary key"));
        }
        // Expiration time = 0, defaults to namespace configuration ("default-ttl")
        Record record = new Record(bins, 0, 0);
        return Pair.create(key, new AerospikeRecord(record));
    }

}
