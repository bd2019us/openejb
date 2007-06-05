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
package org.apache.openejb.config;

import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.ValidationException;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.util.JarUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Unmarshaller {

    private final Class clazz;
    private final File xmlFile;

    private static DTDResolver resolver = new DTDResolver();

    public Unmarshaller(Class type, String xmlFileName) {
        this.clazz = type;
        this.xmlFile = new File(xmlFileName);
    }

    public static Object unmarshal(Class clazz, String xmlFile, String jarLocation) throws OpenEJBException {
        return new Unmarshaller(clazz, xmlFile).unmarshal(jarLocation);
    }


    public static Object unmarshal(Class clazz, String xmlFile) throws OpenEJBException {
        try {
            if (xmlFile.startsWith("jar:")) {
                URL url = new URL(xmlFile);
                xmlFile = url.getFile();
            }
            if (xmlFile.startsWith("file:")){
                URL url = new URL(xmlFile);
                xmlFile = url.getFile();
            }
        } catch (MalformedURLException e) {
            throw new OpenEJBException("Unable to resolve location "+xmlFile,e);
        }

        String jarLocation = null;
        int jarSeparator = xmlFile.indexOf("!");
        if (jarSeparator > 0) {
            jarLocation = xmlFile.substring(0, jarSeparator);
            xmlFile = xmlFile.substring(jarSeparator + 2);
        } else {
            File file = new File(xmlFile);
            xmlFile = file.getName();
            jarLocation = file.getParent();
        }

        return new Unmarshaller(clazz, xmlFile).unmarshal(jarLocation);
    }

    public Object unmarshal(String location) throws OpenEJBException {
        File file = new File(location);
        if (file.isDirectory()) {
            return unmarshalFromDirectory(file);
        } else {
            return unmarshalFromJar(file);
        }
    }

    public Object unmarshalFromJar(File jarFile) throws OpenEJBException {
        String jarLocation = jarFile.getPath();
        String file = xmlFile.getName();

        JarFile jar = JarUtils.getJarFile(jarLocation);
        JarEntry entry = jar.getJarEntry(xmlFile.getPath().replaceAll("\\\\", "/"));

        if (entry == null) throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotFindFile", file, jarLocation));

        Reader reader = null;
        InputStream stream = null;

        try {
            stream = jar.getInputStream(entry);
            reader = new InputStreamReader(stream);
            return unmarshalObject(reader, file, jarLocation);
        } catch (IOException e) {
            throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotRead", file, jarLocation, e.getLocalizedMessage()), e);
        } finally {
            try {
                if (stream != null) stream.close();
                if (reader != null) reader.close();
                if (jar != null) jar.close();
            } catch (Exception e) {
                throw new OpenEJBException(EjbJarUtils.messages.format("file.0020", jarLocation, e.getLocalizedMessage()), e);
            }
        }
    }

    public Object unmarshalFromDirectory(File directory) throws OpenEJBException {
        String file = xmlFile.getName();

        Reader reader = null;
        InputStream stream = null;

        try {
            File fullPath = new File(directory, xmlFile.getPath());
            stream = new FileInputStream(fullPath);
            reader = new InputStreamReader(stream);
            return unmarshalObject(reader, file, directory.getPath());
        } catch (FileNotFoundException e) {
            throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotFindFile", file, directory.getPath()), e);
        } finally {
            try {
                if (stream != null) stream.close();
                if (reader != null) reader.close();
            } catch (Exception e) {
                throw new OpenEJBException(EjbJarUtils.messages.format("file.0020", directory.getPath(), e.getLocalizedMessage()), e);
            }
        }
    }

    public Object unmarshal(URL url) throws OpenEJBException {
        String file = xmlFile.getName();

        Reader reader = null;
        InputStream stream = null;

        try {
            URL fullURL = new URL(url, xmlFile.getPath());
            stream = fullURL.openConnection().getInputStream();
            reader = new InputStreamReader(stream);
            return unmarshalObject(reader, file, fullURL.getPath());
        } catch (MalformedURLException e) {
            throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotFindFile", file, url.getPath()), e);
        } catch (IOException e) {
            throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotRead", file, url.getPath(), e.getLocalizedMessage()), e);
        } finally {
            try {
                if (stream != null) stream.close();
                if (reader != null) reader.close();
            } catch (Exception e) {
                throw new OpenEJBException(EjbJarUtils.messages.format("file.0020", url.getPath(), e.getLocalizedMessage()), e);
            }
        }
    }

    private Object unmarshalObject(Reader reader, String file, String jarLocation) throws OpenEJBException {
        try {
            org.exolab.castor.xml.Unmarshaller unmarshaller = new org.exolab.castor.xml.Unmarshaller(clazz);
            unmarshaller.setWhitespacePreserve(true);
            unmarshaller.setEntityResolver(resolver);
            return unmarshaller.unmarshal(reader);
        } catch (MarshalException e) {
            if (e.getCause() instanceof UnknownHostException) {
                throw new OpenEJBException(EjbJarUtils.messages.format("xml.unkownHost", file, jarLocation, e.getLocalizedMessage()), e);
            } else if (e.getCause() instanceof org.xml.sax.SAXException) {
                throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotParse", file, jarLocation, e.getLocalizedMessage()), e);
            } else if (e.getCause() instanceof IOException) {
                throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotRead", file, jarLocation, e.getLocalizedMessage()), e);
            } else if (e.getCause() instanceof ValidationException) {
                throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotValidate", file, jarLocation, e.getLocalizedMessage()), e);
            } else {
                throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotUnmarshal", file, jarLocation, e.getLocalizedMessage()), e);
            }
        } catch (ValidationException e) {
            throw new OpenEJBException(EjbJarUtils.messages.format("xml.cannotValidate", file, jarLocation, e.getLocalizedMessage()), e);
        }
    }
}
