package org.zoodb.jdo.internal.server.index;

import java.util.NoSuchElementException;
import java.util.Stack;

import javax.jdo.JDOFatalDataStoreException;

import org.zoodb.jdo.internal.server.index.AbstractPagedIndex.AbstractPageIterator;
import org.zoodb.jdo.internal.server.index.LLIterator.IteratorPos;
import org.zoodb.jdo.internal.server.index.PagedUniqueLongLong.LLEntry;

/**
 * Descending iterator.
 * @author Tilmann Z�schke
 */
class LLDescendingIterator extends AbstractPageIterator<LLEntry> {

    private LLIndexPage currentPage = null;
    private short currentPos = 0;
    private final long minKey;
    private final long maxKey;
    private final Stack<IteratorPos> stack = new Stack<IteratorPos>();
    private long nextKey;
    private long nextValue;
    private boolean hasValue = false;
    
    public LLDescendingIterator(AbstractPagedIndex ind, long maxKey, long minKey) {
        super(ind);
        this.minKey = minKey;
        this.maxKey = maxKey;
        this.currentPage = (LLIndexPage) ind.getRoot();
        this.currentPos = (short)(currentPage.getNEntries()-0);
        
        findFirstPosInPage();
    }

    @Override
    public boolean hasNext() {
        return hasValue;
    }

    
    private void goToNextPage() {
        releasePage(currentPage);
        IteratorPos ip = stack.pop();
        currentPage = (LLIndexPage) ip.page;
        currentPos = ip.pos;
        currentPos--;
        
        while (currentPos < 0) {
            releasePage(currentPage);
            if (stack.isEmpty()) {
                close();
                return;// false;
            }
            ip = stack.pop();
            currentPage = (LLIndexPage) ip.page;
            currentPos = ip.pos;
            currentPos--;
        }

        while (!currentPage.isLeaf) {
            //we are not on the first page here, so we can assume that pos=0 is correct to 
            //start with

            //read last page
            stack.push(new IteratorPos(currentPage, currentPos));
            currentPage = (LLIndexPage) findPage(currentPage, currentPos);
            currentPos = currentPage.getNEntries();
        }
        //leaf page positions are smaller than inner-page positions
        currentPos--;
    }
    
    
    private boolean goToFirstPage() {
		while (!currentPage.isLeaf) {
            //the following is only for the initial search.
            //The stored value[i] is the min-values of the according page[i+1}
            int pos = currentPage.binarySearch(0, currentPos, maxKey, Long.MAX_VALUE);
            if (currentPage.getNEntries() == -1) {
            	return false;
            }
            if (pos >=0) {
                pos++;
            } else {
                pos = -(pos+1);
            }
            currentPos = (short) pos;
            
            //read page
		    //Unlike the ascending iterator, we don't need special non-unique stuff here
            stack.push(new IteratorPos(currentPage, currentPos));
            currentPage = (LLIndexPage) findPage(currentPage, currentPos);
            currentPos = currentPage.getNEntries();
        }
		return true;
    }
    
    private void gotoPosInPage() {
        //when we get here, we are on a valid page with a valid position 
    	//(TODO check for pos after goToPage())
        //we only need to check the value.
        
        nextKey = currentPage.getKeys()[currentPos];
        nextValue = currentPage.getValues()[currentPos];
        hasValue = true;
        currentPos--;
        
        //now progress to next element
        
        //first progress to next page, if necessary.
        if (currentPos < 0) {
            goToNextPage();
            if (currentPage == null) {
                return;
            }
        }
        
        //check for invalid value
        if (currentPage.getKeys()[currentPos] < minKey) {
            close();
        }
    }

    private void findFirstPosInPage() {
        //find first page
        if (!goToFirstPage()) {
        	close();
        	return;
        }

        //find very first element. 
		int pos = (short) currentPage.binarySearch(0, currentPage.getNEntries(), maxKey, Long.MAX_VALUE);
        if (pos < 0) {
            pos = -(pos+2); //-(pos+1);
        }
        currentPos = (short) pos;
        
        //check pos
        if (currentPos < 0 || currentPage.getKeys()[currentPos] < minKey) {
            close();
            return;
        }
        gotoPosInPage();
    }
    
    
    @Override
    public LLEntry next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        LLEntry e = new LLEntry(nextKey, nextValue);
        if (currentPage == null) {
            hasValue = false;
        } else {
            gotoPosInPage();
        }
        return e;
    }

    @Override
    public void remove() {
        //As defined in the JDO 2.2. spec:
        throw new UnsupportedOperationException();
    }
    
    /**
     * This method is possibly not be called if the iterator is used in 'for ( : ext) {}' 
     * constructs! 
     */
    public void close() {
        // after close() everything should throw NoSuchElementException (see 2.2. spec)
        currentPage = null;
        super.close();
    }

    @Override
    boolean pageIsRelevant(AbstractIndexPage aiPage) {
        if (!hasNext()) {
            return false;
        }
        
        LLIndexPage page = (LLIndexPage) aiPage;
        if (page == currentPage) {
            return true;
        }
        if (page.getParent() == null) {
            //if anything has been cloned, then the root page has been cloned as well.
            return true;
        }
        
        //leaf page?
        if (page.isLeaf) {
            if (page.getNEntries() == 0) {
                //NO: this must be a new page (isLeaf==true and isEmpty), so we are not interested.
                //YES: This is an empty tree, so we must clone it.
                //Strange: for descendingIterator, this needs to return true. For ascending, 
                //it doesn't seem to matter.
                return false;
            }
            long[] keys = page.getKeys();
            if (minKey > keys[page.getNEntries()-1]
                    || (nextKey < keys[0])
                    || (nextKey == keys[0] && nextValue < keys[0])
            ) {
                return false;
            }
            return true;
        }
        
        //inner page
        //specific to forward iterator
        if (nextKey < findPreceedingKeyOrMVInParents(page)) {
            return false;
        }
        //check min max
        if (minKey > findFollowingKeyOrMVInParents(page)) {
            return false;
        }
        return true;
    }

    /**
     * This finds the highest key of the previous page. The returned key may or may not be
     * smaller than the lowest key in the current branch.
     * @param stackPos
     * @return Key below min (Unique trees) or key equal to min, or MIN_VALUE, if we are on the
     * left side of the tree. 
     */
    private long findPreceedingKeyOrMVInParents(LLIndexPage child) {
        LLIndexPage parent = child.getParent();
        //TODO optimize search? E.g. can we use the pos from the stack here????
        int i = 0;
        for (i = 0; i < parent.getNEntries(); i++) {
            if (parent.leaves[i] == child) {
                break;
            }
        }
        if (i > 0) {
            return parent.getKeys()[i-1];
        }
        //so p==0 here
        if (parent.getParent() == null) {
            //no root
            return Long.MIN_VALUE;
        }
        return findPreceedingKeyOrMVInParents(parent);
    }
    
    /**
     * Finds the maximum key of sub-pages by looking at parent pages. The returned value is
     * probably inclusive, but may no actually be in any child page, in case it has been 
     * removed. (or are parent updated in that case??? I don't think so. The value would become
     * more accurate for the lower page, but worse for the higher page. But would that matter?
     * @param stackPos
     * @return Probable MAX value or MAX_VALUE, if the highest value is unknown.
     */
    private long findFollowingKeyOrMVInParents(LLIndexPage child) {
        LLIndexPage parent = child.getParent();
        for (int i = 0; i < parent.getNEntries(); i++) {
            if (parent.leaves[i] == child) {
                return parent.getKeys()[i];
            }
        }
        if (parent.leaves[parent.getNEntries()] == child) {
            if (parent.getParent() == null) {
                return Long.MAX_VALUE;
            }
            return findFollowingKeyOrMVInParents(parent);
        }
        throw new JDOFatalDataStoreException("Leaf not found in parent page.");
    }

    @Override
    void replaceCurrentAndStackIfEqual(AbstractIndexPage equal,
            AbstractIndexPage replace) {
        if (currentPage == equal) {
            currentPage = (LLIndexPage) replace;
            return;
        }
        for (IteratorPos p: stack) {
            if (p.page == equal) {
                p.page = replace;
                return;
            }
        }
    }
}