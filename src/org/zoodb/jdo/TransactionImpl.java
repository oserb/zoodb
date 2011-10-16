package org.zoodb.jdo;

import java.util.Properties;

import javax.jdo.JDOUserException;
import javax.jdo.PersistenceManager;
import javax.jdo.Transaction;
import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.zoodb.jdo.internal.DataStoreHandler;
import org.zoodb.jdo.internal.Session;

/**
 *
 * @author Tilmann Zaeschke
 */
public class TransactionImpl implements Transaction {

    private volatile boolean isOpen = false;
    //The final would possibly avoid garbage collection
    private final PersistenceManagerImpl pm;
    private Synchronization sync = null;
    private volatile boolean retainValues = false;
    
    private final Session connection;

    /**
     * @param arg0
     * @param pm
     * @param i 
     */
    TransactionImpl(Properties arg0, PersistenceManagerImpl pm, 
            boolean retainValues, boolean isOptimistic, Session con) {
        DataStoreHandler.connect(arg0);
        this.retainValues = retainValues;
        this.pm = pm;
        this.connection = con;
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#begin()
     */
    public synchronized void begin() {
        if (isOpen) {
            throw new JDOUserException(
                    "Can't open new transaction inside existing transaction.");
        }
        isOpen = true;
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#commit()
     */
    public synchronized void commit() {
    	if (!isOpen) {
    		throw new JDOUserException("Can't commit closed " +
    		"transaction. Missing 'begin()'?");
    	}

    	//synchronisation #1
    	isOpen = false;
    	if (sync != null) {
    		sync.beforeCompletion();
    	}

    	//commit
    	connection.commit(retainValues);

    	//synchronization #2
    	if (sync != null) {
    		sync.afterCompletion(Status.STATUS_COMMITTED);
    	}
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#commit()
     */
    public synchronized void rollback() {
    	if (!isOpen) {
    		throw new JDOUserException("Can't rollback closed " +
    		"transaction. Missing 'begin()'?");
    	}
    	isOpen = false;
    	//Don't call beforeCompletion() here.
    	connection.rollback();
    	if (sync != null) {
    		sync.afterCompletion(Status.STATUS_ROLLEDBACK);
    	}
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#getPersistenceManager()
     */
    public PersistenceManager getPersistenceManager() {
        //Not synchronised, field is final
        return pm;
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#isActive()
     */
    public boolean isActive() {
        //Not synchronised, field is volatile
        return isOpen;
    }
    
    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#getSynchronization()
     */synchronized 
    public Synchronization getSynchronization() {
        return sync;
    }

    /**
     * @see org.zoodb.jdo.oldStuff.Transaction#setSynchronization(
     * javax.Transaction.Synchronization)
     */
    public synchronized void setSynchronization(Synchronization sync) {
        this.sync = sync;
    }

	public String getIsolationLevel() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getNontransactionalRead() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getNontransactionalWrite() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getOptimistic() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getRestoreValues() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getRetainValues() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public boolean getRollbackOnly() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setIsolationLevel(String arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setNontransactionalRead(boolean arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setNontransactionalWrite(boolean arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setOptimistic(boolean arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setRestoreValues(boolean arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setRetainValues(boolean arg0) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}

	public void setRollbackOnly() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}
}
