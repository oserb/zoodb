/*
 * Copyright 2009-2013 Tilmann Zaeschke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;

import javax.jdo.JDOUserException;
import javax.jdo.PersistenceManager;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.zoodb.jdo.api.ZooClass;
import org.zoodb.jdo.api.ZooHandle;
import org.zoodb.jdo.api.ZooSchema;
import org.zoodb.test.testutil.TestTools;

public class Test_036_SchemaInstanceHandling {
	
	private PersistenceManager pm;
	
	@AfterClass
	public static void tearDown() {
	    TestTools.closePM();
	}

	@Before
	public void before() {
		TestTools.createDb();
		TestTools.closePM();
		pm = TestTools.openPM();
		pm.currentTransaction().begin();
		ZooSchema.defineClass(pm, TestClassTiny.class);
		ZooSchema.defineClass(pm, TestClassTiny2.class);
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
	}
	
	@After
	public void after() {
		pm.currentTransaction().rollback();
		TestTools.closePM();
		pm = null;
		TestTools.removeDb();
	}

	
	@Test
	public void testNewInstanceWithOidFail1() {
		ZooClass c1 = ZooSchema.locateClass(pm, TestClassTiny.class);
		ZooHandle hdl1 = c1.newInstance();
		try {
			c1.newInstance((Long)hdl1.getOid());
			fail();
		} catch (IllegalArgumentException e) {
			//good
		}
	}
	
	@Test
	public void testNewInstanceWithOidFail2() {
		ZooClass c1 = ZooSchema.locateClass(pm, TestClassTiny.class);
		TestClassTiny t = new TestClassTiny();
		pm.makePersistent(t);
		try {
			c1.newInstance((Long)pm.getObjectId(t));
			fail();
		} catch (IllegalArgumentException e) {
			//good
		}
	}
	
	@Test
	public void testNewInstance2PC() {
		ZooClass c1 = ZooSchema.locateClass(pm, TestClassTiny.class);
		ZooHandle hdl1 = c1.newInstance();
		try {
			//converting a new Handle to an object is not allowed/supported
			hdl1.getJavaObject();
			fail();
		} catch (UnsupportedOperationException e) {
			//good
		}
	}
	
	@Test
	public void testUniqueHandle() {
		ZooClass c1 = ZooSchema.locateClass(pm, TestClassTiny.class);
		ZooHandle hdl1 = c1.newInstance();
		
		ZooHandle hdl2 = ZooSchema.getHandle(pm, hdl1.getOid());
		assertTrue(hdl1 == hdl2);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		ZooHandle hdl3 = ZooSchema.getHandle(pm, hdl1.getOid());
		assertTrue(hdl1 == hdl3);
		
		Iterator<ZooHandle> hIt = 
				ZooSchema.locateClass(pm, TestClassTiny.class).getHandleIterator(false);
		ZooHandle hdl4 = hIt.next();
		assertEquals(hdl1.getOid(), hdl4.getOid());
		assertTrue(hdl4 == hdl1);
	}
	
	@Test
	public void testPc2HandleWithNew() {
		final int I = 123;
		TestClassTiny t1 = new TestClassTiny();
		t1.setInt(I);
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);
		try {
			//handles on new/dirty Java objects are not supported
			ZooSchema.getHandle(pm, oid1);
			fail();
		} catch (UnsupportedOperationException e) {
			//good
		}
//		assertEquals(t1.getInt(), hdl.getValue("_int"));
//		assertTrue(t1 == hdl.getJavaObject());
	}
	
	@Test
	public void testPc2Handle() {
		final int I = 123; //to avoid activation of t1
		TestClassTiny t1 = new TestClassTiny();
		t1.setInt(I);
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
		assertEquals(I, hdl.getValue("_int"));  //'I' avoids activation of t1
		assertTrue(t1 == hdl.getJavaObject());
	}
	
	@Test
	public void testGo2Pc() {
		final int I = 123; //to avoid activation of t1
		TestClassTiny t1 = new TestClassTiny();
		t1.setInt(I);
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);
		
		pm.currentTransaction().commit();
		TestTools.closePM();
		pm = TestTools.openPM();
		pm.currentTransaction().begin();
		
		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
		assertEquals(I, hdl.getValue("_int"));  //'I' avoids activation of t1
		//no load the PCI
		t1 = (TestClassTiny) pm.getObjectById(oid1);
		assertEquals(I, t1.getInt());  //activation of t1
		
		//check identity
		assertNotNull(t1);
		assertTrue(t1 == hdl.getJavaObject());
	}
	
	/**
	 * Verify that commit fails if both the PC and the GO are dirty-new.
	 */
	@Test
	public void testDoubleDirtyNewFail() {
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);
		try {
			//handles on new/dirty Java objects are not supported
			ZooSchema.getHandle(pm, oid1);
			fail();
		} catch (UnsupportedOperationException e) {
			//good
		}
//		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
//		hdl.setValue("_int", 3);
//		
//		try {
//			pm.currentTransaction().commit();
//			fail();
//		} catch (JDOUserException e) {
//			//good
//		}
	}
	
	/**
	 * Verify that commit fails if both the PC and the GO are dirty.
	 */
	@Test
	public void testDoubleDirtyFail() {
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
		hdl.setValue("_int", 3);
		t1.setLong(5);
		
		try {
			pm.currentTransaction().commit();
			fail();
		} catch (JDOUserException e) {
			//good
		}
	}
	
	@Test
	public void testGetJavaObjectFailForClassName() {
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);

		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		//rename class
		ZooSchema.locateClass(pm, TestClassTiny.class).rename("x.y.z");
		
		pm.currentTransaction().commit();
		TestTools.closePM();
		
		pm = TestTools.openPM();
		pm.currentTransaction().begin();
		
		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
		try {
			hdl.getJavaObject();
			fail();
		} catch (JDOUserException e) {
			//good, there is no class x.y.z
		}
	}
	
	@Test
	public void testGetJavaObjectFailForFieldName() {
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		long oid1 = (Long) pm.getObjectId(t1);

		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		//rename class
		ZooSchema.locateClass(pm, TestClassTiny.class).getField("_int").rename("_int2");
		
		pm.currentTransaction().commit();
		TestTools.closePM();
		
		pm = TestTools.openPM();
		pm.currentTransaction().begin();
		
		ZooHandle hdl = ZooSchema.getHandle(pm, oid1);
		try {
			hdl.getJavaObject();
			fail();
		} catch (JDOUserException e) {
			//good, there is no field _int2
		}
	}
	

}
