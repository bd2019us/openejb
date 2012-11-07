/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.server;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * This implementation is mainly used in the application composer to get the most from
 * The EnableServices annotation created
 */
public class FilteredServiceManager extends SimpleServiceManager {

    private Collection<String> services;

    public FilteredServiceManager (String[] services) {
        setServiceManager(this);
        this.services = convertServices(services);

    }

    private Collection<String> convertServices(String[] services) {
        Set<String> realServices = new HashSet<String>();
        Collection<String> rsAliases = Arrays.asList("rest", "jaxrs", "jax-rs", "cxf-rs");
        Collection<String> wsAliases = Arrays.asList("jaxws", "jax-ws", "cxf");

        for (String service : services) {
            if (rsAliases.contains(service)) {
                realServices.addAll(Arrays.asList("cxf-rs", "httpejbd"));
            }
            if (wsAliases.contains(service)) {
                realServices.addAll(Arrays.asList("cxf", "httpejbd"));
            }
            if ("ejbd".equals(service)) {
                realServices.add("httpejbd");
            }
        }
        return realServices;
    }

    @Override
    protected boolean accept(String serviceName) {
        return services.isEmpty() || services.contains(serviceName);
    }

    // used by reflection
    public static void initServiceManager(String[] services) {
        setServiceManager(new FilteredServiceManager(services));
    }
}
