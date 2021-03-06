/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.apache.catalina.core;


import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;


/**
 * Facade for AppalicationFilterConfig.
 *
 * @author Remy Maucherat
 * @version $Revision: 992 $ $Date: 2009-04-08 01:09:34 +0200 (Wed, 08 Apr 2009) $
 */

public class ApplicationFilterConfigFacade implements FilterConfig, FilterRegistration {


    public static class Dynamic extends ApplicationFilterConfigFacade
    implements FilterRegistration.Dynamic {

        public Dynamic(ApplicationFilterConfig config) {
            super(config);
        }

    }


    // ----------------------------------------------------------- Constructors


    public ApplicationFilterConfigFacade(ApplicationFilterConfig config) {
        this.config = config;
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The Context with which we are associated.
     */
    private ApplicationFilterConfig config = null;


    // --------------------------------------------------- FilterConfig Methods


    /**
     * Return the name of the filter we are configuring.
     */
    public String getFilterName() {
        return config.getFilterName();
    }


    /**
     * Return a <code>String</code> containing the value of the named
     * initialization parameter, or <code>null</code> if the parameter
     * does not exist.
     *
     * @param name Name of the requested initialization parameter
     */
    public String getInitParameter(String name) {
        return config.getInitParameter(name);
    }


    /**
     * Return an <code>Enumeration</code> of the names of the initialization
     * parameters for this Filter.
     */
    public Enumeration<String> getInitParameterNames() {
        return config.getInitParameterNames();
    }


    /**
     * Return the ServletContext of our associated web application.
     */
    public ServletContext getServletContext() {
        return config.getServletContext();
    }


    public void addMappingForServletNames(
            EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
            String... servletNames) {
        config.addMappingForServletNames(dispatcherTypes, isMatchAfter, servletNames);
    }


    public void addMappingForUrlPatterns(
            EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
            String... urlPatterns) {
        config.addMappingForUrlPatterns(dispatcherTypes, isMatchAfter, urlPatterns);
    }


    public boolean setInitParameter(String name, String value) {
        return config.setInitParameter(name, value);
    }


    public Set<String> setInitParameters(Map<String, String> initParameters) {
        return config.setInitParameters(initParameters);
    }


    public void setAsyncSupported(boolean isAsyncSupported) {
        config.setAsyncSupported(isAsyncSupported);
    }

    public void setDescription(String description) {
        config.setDescription(description);
    }


    public Collection<String> getServletNameMappings() {
        return config.getServletNameMappings();
    }


    public Collection<String> getUrlPatternMappings() {
        return config.getUrlPatternMappings();
    }


    public String getClassName() {
        return config.getFilterDef().getFilterClass();
    }


    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(config.getFilterDef().getParameterMap());
    }


    public String getName() {
        return config.getFilterName();
    }

}
