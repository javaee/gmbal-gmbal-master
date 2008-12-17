/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.gmbal.impl;

import org.glassfish.gmbal.generic.DprintUtil;
import org.glassfish.gmbal.generic.FacetAccessor;
import java.util.HashMap;
import java.util.Map;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanRegistrationException;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.NotificationEmitter;
import javax.management.ObjectName;
import org.glassfish.gmbal.GmbalException;

/** Represents the collection of DynamicMBeanImpls that we have registered with
 * a ManagedObjectManager.
 *
 * XXX Need to get some benchmarks for registration cost.
 * XXX Do we need to enable/disable MBean registration with MBeanServer.
 *
 * @author ken
 */
public class MBeanTree {
    private boolean rootIsSet = false ;
    private Object root ;
    private MBeanImpl rootEntity ;
    private Map<Object,MBeanImpl> objectMap ;
    private Map<ObjectName,Object> objectNameMap ;
    private String domain ;
    private ObjectName rootParentName ;
    private String rootParentSuffix ;
    private String typeString ; // What string is used for the type of the 
                                // type name/value pair?
    private ManagedObjectManagerInternal mom ;
    private DprintUtil dputil ;
    
    private void addToObjectMaps( MBeanImpl mbean ) {
        ObjectName oname = mbean.objectName() ;
        for (Object obj : mbean.facets() ) {
            objectMap.put( obj, mbean ) ;
        }
        objectNameMap.put( oname, mbean ) ;
    }
    
    private void removeFromObjectMaps( MBeanImpl mbean ) {
        ObjectName oname = mbean.objectName() ;
        for (Object obj : mbean.facets() ) {
            objectMap.remove( obj ) ;
        }
        
        objectNameMap.remove( oname ) ;
    }
    
    public synchronized NotificationEmitter setRoot( Object root, String rootName ) {
        if (rootIsSet) {
            throw new IllegalStateException( 
                "Root has already been set: cannot set it again" ) ;
        } else {
            rootIsSet = true ;
        }
        
        // Now register the root MBean.
        MBeanImpl rootMB = mom.constructMBean( root, rootName ) ;
        
        ObjectName oname ;
        try {
            oname = objectName(null, rootMB.type(), rootMB.name());
        } catch (MalformedObjectNameException ex) {
            throw new IllegalArgumentException( 
                "Could not construct ObjectName for root", 
                ex ) ;            
        }
        rootMB.objectName( oname ) ;
        
        addToObjectMaps( rootMB ) ;
        
        try {
            rootMB.register();
        } catch (JMException ex) {
            throw new IllegalArgumentException( "Could not register root", 
                ex ) ;
        }
        
        this.root = root ;
        rootEntity = rootMB ;
        return rootMB ;
    }
    
    public synchronized Object getRoot() {
        if (rootIsSet) {
            return root ;
        } else {
            throw new IllegalStateException( "Root has not yet been set" ) ;
        }   
    }

    private String getRootParentSuffix( final ObjectName rootParentName ) {
        final String[] keys =
            rootParentName.getKeyPropertyListString().split( "," ) ;

        final StringBuilder res = new StringBuilder() ;
        String typeValue = null ;
        String nameValue = null ;
        for (String str : keys) {
            int index = str.indexOf( '=' ) ;
            String key = str.substring( 0, index ) ;
            String value = str.substring( index+1 ) ;
            if (key.equals( "type" ) || key.equals( "j2eeType" ) ) {
                typeValue = value ;
            } else if (key.equals( "name" ) ) {
                nameValue = value ;
            } else {
                res.append( ',' ) ;
                res.append( key ) ;
                res.append( '=' ) ;
                res.append( value ) ;
            }
        }

        if (typeValue == null || nameValue == null)
            throw new GmbalException( "rootParentName " + rootParentName
                + " is invalid: it is missing type or name" ) ;

        final String result = typeValue + '=' + nameValue + res.toString() ;
        return result ;
    }

    public MBeanTree( final ManagedObjectManagerInternal mom,
        final String domain, 
        final ObjectName rootParentName,
        final String typeString ) {
        
        this.mom = mom ;
        this.domain = domain ;
        this.rootParentName = rootParentName ;
        if (rootParentName == null) {
            rootParentSuffix = null ;
        } else {
            rootParentSuffix = getRootParentSuffix( rootParentName ) ;
        }

        this.typeString = typeString ;
        objectMap = new HashMap<Object,MBeanImpl>() ;
        objectNameMap = new HashMap<ObjectName,Object>() ;
        dputil = new DprintUtil( getClass() ) ;
    }

    public synchronized FacetAccessor getFacetAccessor(Object obj) {
        return objectMap.get( obj ) ;
    }
    
    private boolean notEmpty( String str ) {
        return str != null && str.length() > 0 ;
    }
    
    private void checkCorrectRoot( MBeanImpl entity ) {
        MBeanImpl current = entity ;
        do {
            if (current == rootEntity) {
                return ;
            }
            
            current = current.parent() ;
        } while (current != null) ;
        
        throw new IllegalArgumentException( "Entity " + entity 
            + " is not part of this EntityTree" ) ;
    }
    
    public synchronized ObjectName objectName( MBeanImpl parent,
        String type, String name ) 
        throws MalformedObjectNameException {
        
        if (parent != null) {
            checkCorrectRoot( parent ) ;
        }

        StringBuilder result = new StringBuilder() ;

        result.append( domain ) ;
        result.append( ":" ) ;

        result.append( typeString ) ;
        result.append( "=" ) ;
        result.append( type ) ;

        result.append( ',') ;
        result.append( "name" ) ;
        result.append( "=" ) ;
        result.append( name ) ;

        if (parent != null) {
            result.append( ',' ) ;
            result.append( parent.restName() ) ;
        }

        if (rootParentSuffix != null) {
            result.append( ',' ) ;
            result.append( rootParentSuffix ) ;
        }

        return new ObjectName( result.toString() ) ; 
    }
    
    public synchronized NotificationEmitter register( 
        final Object parent, 
        final Object obj, 
        final MBeanImpl mb ) throws InstanceAlreadyExistsException, 
        MBeanRegistrationException, NotCompliantMBeanException, 
        MalformedObjectNameException {
        
        if (mom.registrationDebug()) {
            dputil.enter( "register", 
                "parent=", parent,
                "obj=", obj,
                "mb=", mb ) ;
        }
        
        try { 
            if (parent == null) {
                throw new IllegalArgumentException( "Parent cannot be null" ) ;
            }
            
            MBeanImpl oldMB = objectMap.get( obj ) ;
            if (oldMB != null) {
                String msg = "Object " + obj + " is already registered as " 
                    + oldMB ;
                
                if (mom.registrationDebug()) {
                    dputil.info( msg ) ;
                }
                
                throw new IllegalArgumentException( msg ) ;
            }
            
            MBeanImpl parentEntity ;

            parentEntity = objectMap.get( parent ) ;
            if (parentEntity == null) {
                String msg = "parent object " + parent + " not found" ;
                if (mom.registrationDebug()) {
                    dputil.info( msg ) ;
                }
                throw new IllegalArgumentException( msg ) ;
            }
            
            ObjectName oname = objectName( parentEntity, mb.type(), 
                mb.name() ) ;
            mb.objectName( oname ) ;
        
            addToObjectMaps( mb ) ;

            parentEntity.addChild( mb ) ; 

            mb.register() ;

            return mb ;
        } finally {
            if (mom.registrationDebug()) {
                dputil.exit() ;
            }
        }
    }
    
    public synchronized void unregister( Object obj ) 
        throws InstanceNotFoundException, MBeanRegistrationException {
        if (obj == root) {
            rootIsSet = false ;
            root = null ;
            rootEntity = null ;
        }
        
        MBeanImpl mb = objectMap.get( obj ) ;
        for (Map<String,MBeanImpl> nameToMBean : mb.children().values() ) {
            for (MBeanImpl child : nameToMBean.values() ) {
                unregister( child.target()) ;
            }
        }

        removeFromObjectMaps( mb ) ;
        mb.unregister() ;
        
        if (mb.parent() != null) {
            mb.parent().removeChild( mb ) ;
        }
    }
    
    public synchronized ObjectName getObjectName( Object obj ) {
        MBeanImpl result = objectMap.get(obj);
        return result.objectName() ;
    }
    
    public synchronized Object getObject( ObjectName oname ) {
        return objectNameMap.get( oname ) ;
    }
    
    public synchronized MBeanImpl getMBeanImpl( Object obj ) {
        return objectMap.get( obj ) ;
    }
    
    public synchronized void clear(){
        if (rootIsSet) {
            try {
                unregister(root);
            } catch (InstanceNotFoundException ex) {
                throw new IllegalStateException( "Should not happen!", ex ) ;
            } catch (MBeanRegistrationException ex) {
                throw new IllegalStateException( "Should not happen!", ex ) ;
            }
        }
        
        objectMap.clear() ;
        objectNameMap.clear() ;
        rootEntity = null ;
    }

    public ObjectName getRootParentName() {
        return rootParentName ;
    }
}