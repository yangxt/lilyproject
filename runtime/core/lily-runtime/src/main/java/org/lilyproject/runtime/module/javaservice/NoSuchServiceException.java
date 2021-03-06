/*
 * Copyright 2013 NGDATA nv
 * Copyright 2008 Outerthought bvba and Schaubroeck nv
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
package org.lilyproject.runtime.module.javaservice;

import org.lilyproject.runtime.LilyRTException;

public class NoSuchServiceException extends LilyRTException {
    private String type;
    private String moduleId;
    private String name;

    public NoSuchServiceException(String type) {
        super("No Java service is available for type " + type);
        this.type = type;
    }

    public NoSuchServiceException(String type, String moduleId) {
        super("No Java service is available for type " + type + " by module " + moduleId);
        this.type = type;
        this.moduleId = moduleId;
    }

    public NoSuchServiceException(String type, String moduleId, String name) {
        super("No Java service is available for type " + type + " by module " + moduleId + " named " + name);
        this.type = type;
        this.moduleId = moduleId;
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public String getModuleId() {
        return moduleId;
    }

    public String getName() {
        return name;
    }
}
