/*
 * Copyright 2012 NGDATA nv
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
package org.lilyproject.repository.impl;

import org.lilyproject.repository.api.TableManager.TableCreateDescriptor;
import org.lilyproject.util.hbase.TableConfig;

public class TableCreateDescriptorImpl implements TableCreateDescriptor{

    private String name;
    private byte[][] splitKeys;

    TableCreateDescriptorImpl(String name, byte[][] splitKeys) {
        this.name = name;
        this.splitKeys = splitKeys;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public byte[][] getSplitKeys() {
        return splitKeys;
    }

    public static TableCreateDescriptor createInstance(String name) {
        return new TableCreateDescriptorImpl(name, null);
    }

    public static TableCreateDescriptor createInstanceWithSplitKeys(String name, byte[][] splitKeys) {
        TableConfig tableConfig = new TableConfig(splitKeys);
        return new TableCreateDescriptorImpl(name, tableConfig.getSplitKeys());
    }

    public static TableCreateDescriptor createInstanceWithSplitKeys(String name, String keyPrefix, String splitKeysSpec) {
        byte[][] splitKeys = TableConfig.parseSplitKeys(null, splitKeysSpec, keyPrefix);
        TableConfig tableConfig = new TableConfig(splitKeys);
        return new TableCreateDescriptorImpl(name, tableConfig.getSplitKeys());
    }

    public static TableCreateDescriptor createInstance(String name, String keyPrefix, int numRegions) {
        byte[][] splitKeys = TableConfig.parseSplitKeys(numRegions, null, keyPrefix);
        TableConfig tableConfig = new TableConfig(splitKeys);
        return new TableCreateDescriptorImpl(name, tableConfig.getSplitKeys());
    }

}
