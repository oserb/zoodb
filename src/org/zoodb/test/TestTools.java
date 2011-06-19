package org.zoodb.test;

import java.util.Properties;

import javax.jdo.JDOException;
import javax.jdo.JDOHelper;
import javax.jdo.JDOUserException;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.jdo.spi.PersistenceCapable;

import org.zoodb.jdo.api.Schema;
import org.zoodb.jdo.api.ZooHelper;
import org.zoodb.jdo.api.ZooJdoProperties;

public class TestTools {

	private static final String DB_NAME = "TestDb";
	private static PersistenceManager _pm;

	public static void createDb(String dbName) {
		removeDb(dbName);
		ZooHelper.getDataStoreManager().createDbFolder(dbName);
		ZooHelper.getDataStoreManager().createDbFiles(dbName);
	}
	

	/**
	 * Create the default database.
	 */
	public static void createDb() {
		createDb(DB_NAME);
	}
	
	
	/**
	 * Remove the default database.
	 */
	public static void removeDb() {
		removeDb(DB_NAME);
	}
	
	
	public static void removeDb(String dbName) {
		try {
			closePM();
		} catch (JDOException e) {
			e.printStackTrace();
		}
		try {
			ZooHelper.getDataStoreManager().removeDbFolder(dbName);
		} catch (JDOUserException e) {
			//ignore
		}
		try {
			ZooHelper.getDataStoreManager().removeDbFiles(dbName);
		} catch (JDOUserException e) {
			//ignore
		}
		try {
			ZooHelper.getDataStoreManager().removeDbFolder(dbName);
		} catch (JDOUserException e) {
			//ignore
		}
	}

	/**
	 * Varargs does not seem to work with generics.
	 * @param classes
	 */
    public static void defineSchema(Class<?> ... classes) {
        defineSchema(DB_NAME, classes);
    }
	
    
	public static void defineSchema(String dbName, Class<?> ... classes) {
		Properties props = new ZooJdoProperties(dbName);
		PersistenceManagerFactory pmf = 
			JDOHelper.getPersistenceManagerFactory(props);
		PersistenceManager pm = null;
		try {
		    pm = pmf.getPersistenceManager();

	        pm.currentTransaction().begin();
	        
	        for (Class<?> cls: classes) {
	        	Schema.create(pm, cls, dbName);
	        }

	        pm.currentTransaction().commit();
		} finally {
		    if (pm != null) {
		    	if (pm.currentTransaction().isActive()) {
		    		pm.currentTransaction().rollback();
		    	}
		        pm.close();
		    }
            if (pmf != null) {
                pmf.close();
            }
		}
	}

	public static void removeSchema(String dbName, Class<? extends PersistenceCapable> cls) {
		Properties props = new ZooJdoProperties(dbName);
		PersistenceManagerFactory pmf = 
			JDOHelper.getPersistenceManagerFactory(props);
        PersistenceManager pm = null;
        try {
            pm = pmf.getPersistenceManager();

		Schema.locate(pm, cls, dbName).remove();

		pm.currentTransaction().commit();
        } finally {
            if (pm != null) { 
                pm.close();
            }
            if (pmf != null) {
                pmf.close();
            }
        }
	}


	public static PersistenceManager openPM() {
		Properties props = new ZooJdoProperties(DB_NAME);
		PersistenceManagerFactory pmf = 
			JDOHelper.getPersistenceManagerFactory(props);
		_pm = pmf.getPersistenceManager();
		return _pm;
	}


	public static void closePM(PersistenceManager pm) {
		PersistenceManagerFactory pmf = pm.getPersistenceManagerFactory();
		if (!pm.isClosed()) {
			if (pm.currentTransaction().isActive()) {
				pm.currentTransaction().rollback();
			}
			pm.close();
		}
		pmf.close();
		_pm = null;
	}


	public static void closePM() {
		if (_pm != null)
			closePM(_pm);
	}


	public static String getDbFileName() {
		return ZooHelper.getDataStoreManager().getDbPath(DB_NAME);
	}
}
