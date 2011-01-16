package org.zoodb.jdo.internal.server.index;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Stack;

import javax.jdo.JDOFatalDataStoreException;

import org.zoodb.jdo.internal.server.PageAccessFile;


/**
 * @author Tilmann Z�schke
 */
public class PagedUniqueLongLong extends AbstractPagedIndex {
	
	public static final boolean DEBUG = true;
	
	public static class LLEntry {
		final long key;
		final long value;
		private LLEntry(long k, long v) {
			key = k;
			value = v;
		}
		public long getKey() {
			return key;
		}
		public long getValue() {
			return value;
		}
	}
	
	private static class IteratorPos {
		IteratorPos(AbstractIndexPage page, short pos) {
			this.page = page;
			this.pos = pos;
		}
		//This is for the iterator, do _not_ use WeakRefs here.
		AbstractIndexPage page;
		short pos;
	}

	/**
	 * Some thoughts on Iterators:
	 * 
	 * JDO has a usecase like this:
	 * Iterator iter = extent.iterator();
	 * while (iter.hasNext()) {
	 * 	   pm.deletePersistent(iter.next());
	 * }
	 * 
	 * That means:
	 * The iterator needs to support deletion without introducing duplicates and without skipping 
	 * objects. It needs to be a perfect iterator.
	 * 
	 * According to the spec 2.2., the extent should contain whatever existed a the time of the 
	 * execution of the query or creation of the iterator (JDO 2.2).
	 * 
	 * So:
	 * - Different sessions should use COW to create locally valid 'copies' of the traversed index.
	 * - Within the same session, iterators should support deletion as described above.
	 * 
	 * To support the deletion locally, there are several option:
	 * - one could use COW as well, which would mean that bidirectional iterators would not work,
	 *   because the iterator iterates over copies of the original list. 
	 *   Basically the above example would work, but deletions ahead of the iterator would not
	 *   be recognized (desirable?). TODO Check spec.
	 * - Alternative: Update iterator with callbacks from index modification.
	 *   This would mean ahead-of-iterator modifications would be recognized (desirable?)
	 *   
	 *    
	 *    
	 *    
	 * Version 2.0:
	 * Iterator stores currentElement and immediately moves to next element. For unique indices
	 * this has the advantage, that the will never be buffer pages created, because the index
	 * is invalidated, as soon as it is created.
	 * 
	 * @author Tilmann Z�schke
	 *
	 */
	static class ULLIterator extends AbstractPageIterator<LLEntry> {

		private ULLIndexPage currentPage = null;
		private short currentPos = 0;
		private final long minKey;
		private final long maxKey;
		private final Stack<IteratorPos> stack = new Stack<IteratorPos>();
		private long nextKey;
		private long nextValue;
		private boolean hasValue = false;
		
		public ULLIterator(AbstractPagedIndex ind, long minKey, long maxKey) {
			super(ind);
			this.minKey = minKey;
			this.maxKey = maxKey;
			this.currentPage = (ULLIndexPage) ind.getRoot();

			findFirstPosInPage();
		}

		@Override
		public boolean hasNext() {
			return hasValue;
		}

		
		private void goToNextPage() {
			releasePage(currentPage);
			IteratorPos ip = stack.pop();
			currentPage = (ULLIndexPage) ip.page;
			currentPos = ip.pos;
			currentPos++;
			
			while (currentPos > currentPage.nEntries) {
				releasePage(currentPage);
				if (stack.isEmpty()) {
					close();
					return;// false;
				}
				ip = stack.pop();
				currentPage = (ULLIndexPage) ip.page;
				currentPos = ip.pos;
				currentPos++;
			}

			//TODO are we checking the correct key here? maybe use (-1)???
			//but this is only a shortcut, we don't  really need it.
//			if (currentPos < currentPage.nEntries && currentPage.keys[currentPos] > maxKey) {
//				close();
//				return;
//			}
			
			while (!currentPage.isLeaf) {
				//we are not on the first page here, so we can assume that pos=0 is correct to 
				//start with

				//read last page
				stack.push(new IteratorPos(currentPage, currentPos));
				currentPage = (ULLIndexPage) findPage(currentPage, currentPos);
				currentPos = 0;
			}
		}
		
		
		private void goToFirstPage() {
			while (!currentPage.isLeaf) {
				//the following is only for the initial search.
				//The stored value[i] is the min-values of the according page[i+1}
			    int pos = Arrays.binarySearch(currentPage.keys, currentPos, currentPage.nEntries, minKey);
			    if (pos >=0) {
			        pos++;
			    } else {
			        pos = -(pos+1);
			    }
			    currentPos = (short) pos;
			    
			    //TODO
//			    if (!isUnique) {
//			    	//iterate back
//			    	while(pos > 0 && currentPage.keys[pos] == minKey) {
//			    		pos--;
//			    	}
//			    	//correct pos
//			    	if (currentPage.keys[pos] < minKey) {
//			    		pos++;
//			    	}
//			    	currentPos = (short)pos;
//			    }

				//read last page
				stack.push(new IteratorPos(currentPage, currentPos));
				currentPage = (ULLIndexPage) findPage(currentPage, currentPos);
				currentPos = 0;
			}
		}
		
		private void gotoPosInPage() {
			//when we get here, we are on a valid page with a valid position (TODO check for pos after goToPage())
			//we only need to check the value.
			
			nextKey = currentPage.keys[currentPos];
			nextValue = currentPage.values[currentPos];
			//TODO remove?
			hasValue = true;
			currentPos++;
			
			//now progress to next element
			
			//first progress to next page, if necessary.
			if (currentPos >= currentPage.nEntries) {
				goToNextPage();
				if (currentPage == null) {
					return;
				}
			}
			
			//check for invalid value
			if (currentPage.keys[currentPos] > maxKey) {
				close();
			}
		}

		private void findFirstPosInPage() {
			//find first page
			goToFirstPage();

			//find very first element. 
			//TODO use binary search?
			while (currentPage.keys[currentPos] < minKey && currentPos < currentPage.nEntries) {
				currentPos++;
			}
			if (currentPos >= currentPage.nEntries || currentPage.keys[currentPos] > maxKey) {
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
			//	close();
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
			
			ULLIndexPage page = (ULLIndexPage) aiPage;
			if (page == currentPage) {
				return true;
			}
			if (page.parent == null) {
				//if anything has been cloned, then the root page has been cloned as well.
				return true;
			}
			
			//leaf page?
			if (page.isLeaf) {
				if (currentPage.nEntries == 0) {
					//NO: this must be a new page (isLeaf==true and isEmpty), so we are not interested.
					//YES: This is an empty tree, so we must clone it.
					//Strange: for descendingIterator, this needs to return true. For ascending, 
					//it doesn't seem to matter.
					return false;
				}
				if (maxKey < page.keys[0]
				        || (nextKey > page.keys[page.nEntries-1])
						|| (nextKey == page.keys[page.nEntries-1] && nextValue > page.keys[page.nEntries-1])
				) {
					return false;
				}
				return true;
			}
			
			//inner page
			//specific to forward iterator
			long nextKey2 = findFollowingKeyOrMVInParents(page);
			if (nextKey > nextKey2) {
				return false;
			}
			//check min max
			if (maxKey < findPreceedingKeyOrMVInParents(page)) {
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
		private long findPreceedingKeyOrMVInParents(ULLIndexPage child) {
			ULLIndexPage parent = (ULLIndexPage) child.parent;
			//TODO optimize search? E.g. can we use the pos from the stack here????
			int i = 0;
			for (i = 0; i < parent.nEntries; i++) {
				if (parent.leaves[i] == child) {
					break;
				}
			}
			if (i > 0) {
				return parent.keys[i-1];
			}
			//so p==0 here
			if (parent.parent == null) {
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
		private long findFollowingKeyOrMVInParents(ULLIndexPage child) {
			ULLIndexPage parent = (ULLIndexPage) child.parent;
			for (int i = 0; i < parent.nEntries; i++) {
				if (parent.leaves[i] == child) {
					return parent.keys[i];
				}
			}
			if (parent.leaves[parent.nEntries] == child) {
				if (parent.parent == null) {
					return Long.MAX_VALUE;
				}
				return findFollowingKeyOrMVInParents(parent);
			}
			throw new JDOFatalDataStoreException("Leaf not found in parent page.");
		}

//		/**
//		 * This finds the highest key of the previous page. The returned key may or may not be
//		 * smaller than the lowest key in the current branch.
//		 * @param stackPos
//		 * @return Key below min (Unique trees) or key equal to min, or MIN_VALUE, if we are on the
//		 * left side of the tree. 
//		 */
//		private long findPreceedingKeyOrMVInParents(int stackPos) {
//			IteratorPos p = stack.elementAt(stackPos);
//			if (p.pos > 0) {
//				return ((ULLIndexPage)p.page).keys[p.pos-1];
//			}
//			//so p==0 here
//			if (stackPos == 0) {
//				//no root
//				return Long.MIN_VALUE;
//			}
//			return findPreceedingKeyOrMVInParents(stackPos-1);
//		}
//		
//		/**
//		 * Finds the maximum key of sub-pages by looking at parent pages. The returned value is
//		 * probably inclusive, but may no actually be in any child page, in case it has been 
//		 * removed. (or are parent updated in that case??? I don't think so. The value would become
//		 * more accurate for the lower page, but worse for the higher page. But would that matter?
//		 * @param stackPos
//		 * @return Probable MAX value or MAX_VALUE, if the highest value is unknown.
//		 */
//		private long findFollowingKeyOrMVInParents(int stackPos) {
//			IteratorPos p = stack.elementAt(stackPos);
//			return ((ULLIndexPage)p.page).keys[p.pos];
//		}

		@Override
		void replaceCurrentAndStackIfEqual(AbstractIndexPage equal,
				AbstractIndexPage replace) {
			if (currentPage == equal) {
				currentPage = (ULLIndexPage) replace;
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
	
		
	static class ULLDescendingIterator extends AbstractPageIterator<LLEntry> {

		private ULLIndexPage currentPage;
		private short currentPos = 0;
		private final long minKey;
		private final long maxKey;
		private final Stack<IteratorPos> stack = new Stack<IteratorPos>();
		
		public ULLDescendingIterator(AbstractPagedIndex ind, long maxKey, long minKey) {
			super(ind);
			
			this.minKey = minKey;
			this.maxKey = maxKey;

			//find page
			currentPage = (ULLIndexPage) ind.getRoot();
			currentPos = (short) (currentPage.nEntries);
			navigateToNextLeaf();
			
			// now find pos in this page
			currentPos = (short) (currentPage.nEntries-1);
			while (currentPos > 0 && currentPage.keys[currentPos] > maxKey) {
				currentPos--;
			}

			//next will decrement this
			currentPos ++;
		}

		@Override
		public boolean hasNext() {
			if (currentPage == null) {
				return false;
			}
			if (currentPos-1 >= 0) {
				if (currentPage.keys[currentPos-1] >= minKey) {
					return true;
				}
				close();
				return false;
			}
			//currentPos >= nEntries -> no more on this page
			if (currentPage.parent == null) {
				close();
				return false;
			}
			
			//find next page
			IteratorPos ip = stack.pop();
			currentPage = (ULLIndexPage) ip.page;
			currentPos = ip.pos;
			currentPos--;
			if (!navigateToNextLeaf()) {
				close();
				return false;
			}
			return true;
		}

		private boolean navigateToNextLeaf() {
			//pop up to next parent page that allows navigating backwards
			if (DEBUG) System.out.println("NTNLa1 " + currentPos);
			while (currentPos < -1) {
				if (DEBUG) System.out.println("NTNLa2 " + currentPos);
				releasePage(currentPage);
				if (stack.isEmpty()) {
					if (DEBUG) System.out.println("NTNLa3 " + currentPos);
					return false;
				}
				IteratorPos ip = stack.pop();
				currentPage = (ULLIndexPage) ip.page;
				currentPos = ip.pos;
				currentPos--;
				if (DEBUG) System.out.println("NTNLa4 " + currentPos);
			}

			if (DEBUG) System.out.println("NTNLa5 " + currentPos);

			//walk down to next leaf page
			while (!currentPage.isLeaf) {
				if (DEBUG) System.out.println("NTNLb1 " + currentPos);
				//The stored value[i] is the min-values of the according page[i+1} 
				//TODO implement binary search
				//TODO this should (is?) only used when the starting page is located. After that,
				//     currentPos should be read from the stack.
				for ( ; currentPos > 0; currentPos--) {
					if (DEBUG) System.out.println("NTNLb2 " + currentPos);
					//TODO write >= for non-unique indices. And prepare that previous page may not
					//contain the requested key
					if (currentPage.keys[currentPos] <= maxKey) {
						if (DEBUG) System.out.println("NTNLb3 " + currentPos + " mk=" + maxKey + " " + currentPage.keys[currentPos]);
						//read page after that value
						break;
					}
				}
				if (DEBUG) System.out.println("NTNLb4 " + currentPos);
				
				//read last page
				//position of the key, not of the Page!!!
				stack.push(new IteratorPos(currentPage, currentPos));
				currentPage = (ULLIndexPage) findPage(currentPage, (short)(currentPos+1));
				currentPos = (short) (currentPage.nEntries-1);
				if (DEBUG) System.out.println("NTNLb5 " + currentPos);
			}
			
			if (DEBUG) System.out.println("NTNLc1 " + currentPos);
			//no need to check the pos, each leaf should have more than 0 entries;
			if (currentPos >= 0 && currentPage.keys[currentPos] >= minKey) {
				currentPos++;
				if (DEBUG) System.out.println("NTNLc2 " + currentPos + " mk=" + minKey + " " + currentPage.keys[currentPos]);
				return true;
			}
			return false;
		}
		
		
		@Override
		public LLEntry next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			//hasNext should leave us on a leaf page
			currentPos--;
			return new LLEntry(currentPage.keys[currentPos], currentPage.values[currentPos]);
		}

		@Override
		public void remove() {
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
			ULLIndexPage page = (ULLIndexPage) aiPage;
			if (page == currentPage) {
				return true;
			}
			if (page.parent == null) {
				//if anything has been cloned, then the root page has been cloned as well.
				return true;
			}
			
			if (page.isLeaf) {
				if (page.nEntries == 0) {
					//this must be an empty tree. So we should clone it.
					return true;
				}
				if (minKey > page.keys[page.nEntries-1] ||
						maxKey < page.keys[0]) {
					//this is important for findFollowingKeyOrMVInParents()
					return false;
				}
			}
			
			//specific to backward iterator
			if (currentPage.keys[currentPos] < findPreceedingKeyOrMVInParents(page)) {
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
		private long findPreceedingKeyOrMVInParents(AbstractIndexPage child) {
			ULLIndexPage parent = (ULLIndexPage) child.parent;
			//these algorithms only evere work on parent pages (see above line)
			//TODO optimize search?
			int i = 0;
			for (i = 0; i < parent.nEntries; i++) {
				if (parent.leaves[i] == child) {
					break;
				}
			}
			if (i > 0) {
				return parent.keys[i-1];
			}
			//so p==0 here
			if (parent.parent == null) {
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
		private long findFollowingKeyOrMVInParents(AbstractIndexPage child) {
			ULLIndexPage parent = (ULLIndexPage) child.parent;
			//these algorithms only evere work on parent pages (see above line)
			for (int i = 0; i < parent.nEntries; i++) {
				if (parent.leaves[i] == child) {
					return parent.keys[i];
				}
			}
			if (parent.leaves[parent.nEntries] == child) {
				if (parent.parent == null) {
					//so there is no key after the child page, meaning that the child page is the
					//last page in the tree. 
					//We can't decide here whether the page is relevant, without looking at the leaf
					//again.
					//Since this is the root page, we simply clone it. It is highly likely that we
					//need it anyway later on.
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
				currentPage = (ULLIndexPage) replace;
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
	
	static class ULLIndexPage extends AbstractIndexPage {
		private final long[] keys;
		//TODO store only pages or also offs? -> test de-ser whole page vs de-ser single obj.
		//     -> especially, objects may not be valid anymore (deleted)! 
		private final long[] values;
		/** number of keys. There are nEntries+1 subPages in any leaf page. */
		private short nEntries;
		
		
		public ULLIndexPage(AbstractPagedIndex ind, AbstractIndexPage parent, boolean isLeaf) {
			super(ind, parent, isLeaf);
			if (isLeaf) {
				keys = new long[ind.maxLeafN];
				values = new long[ind.maxLeafN];
			} else {
				keys = new long[ind.maxInnerN];
				values = null;
			}
		}

		public ULLIndexPage(ULLIndexPage p) {
			super(p);
			keys = p.keys.clone();
			nEntries = p.nEntries;
			if (isLeaf) {
				values = p.values.clone();
			} else {
				values = null;
			}
		}
		

		//TODO change this.
		// each class should implement the read() method, and should call the super.readNonLeaf()
		// if necessary. This would avoid some casting. And the super class wouldn't have to call
		// it's own sub-class (code-smell!?).
		
		@Override
		void readData() {
			nEntries = ind.paf.readShort();
			for (int i = 0; i < nEntries; i++) {
				keys[i] = ind.paf.readLong();
				values[i] = ind.paf.readLong();
			}
		}
		
		@Override
		void writeData() {
			ind.paf.writeShort(nEntries);
			for (int i = 0; i < nEntries; i++) {
				ind.paf.writeLong(keys[i]);
				ind.paf.writeLong(values[i]);
			}
		}

		/**
		 * Locate the (first) page that could contain the given key.
		 * In the inner pages, the keys are the minimum values of the following page.
		 * @param key
		 * @return Page for that key
		 */
		public ULLIndexPage locatePageForKey(long key, boolean allowCreate) {
			if (isLeaf) {
				return this;
			}
			//The stored value[i] is the min-values of the according page[i+1} 
            short pos = (short) Arrays.binarySearch(keys, 0, nEntries, key);
            if (pos >= 0) {
                //pos of matching key
                pos++;
            } else {
                pos = (short) -(pos+1);
                if (pos == nEntries) {
                    //read last page, but does it exist?
                    if (leaves[nEntries]==null && leafPages[nEntries] == 0 && !allowCreate) {
                        return null;
                    }
                }
            }
            //TODO use weak refs
            //read page before that value
            ULLIndexPage page = (ULLIndexPage) readOrCreatePage(pos, allowCreate);
            return page.locatePageForKey(key, allowCreate);
		}
		
		public LLEntry getValueFromLeaf(long oid) {
			if (!isLeaf) {
				throw new JDOFatalDataStoreException();
			}
			int pos = Arrays.binarySearch(keys, 0, nEntries, oid);
			if (pos >= 0) {
                return new LLEntry( oid, values[pos]);
			}
			//TODO if non-unique, the value could be on the following page!
			return null;
		}

		public void put(long key, long value) {
			if (!isLeaf) {
				throw new JDOFatalDataStoreException();
			}
			if (nEntries < ind.maxLeafN) {
				//add locally
	            int pos = Arrays.binarySearch(keys, 0, nEntries, key);
	            //key found? -> pos >=0
	            if (pos >= 0) {
	                if (value != values[pos]) {
	                    markPageDirty();
	                    values[pos] = value;
	                }
	                return;
	            } 
	            //okay so we add it
	            pos = -(pos+1);
	            markPageDirty();
	            if (pos < nEntries) {
	                System.arraycopy(keys, pos, keys, pos+1, nEntries-pos);
	                System.arraycopy(values, pos, values, pos+1, nEntries-pos);
	            }
	            keys[pos] = key;
	            values[pos] = value;
	            nEntries++;
	            return;
			} else {
				//treat page overflow
				ULLIndexPage newP = new ULLIndexPage(ind, parent, true);
				markPageDirty();
				System.arraycopy(keys, ind.minLeafN, newP.keys, 0, ind.maxLeafN-ind.minLeafN);
				System.arraycopy(values, ind.minLeafN, newP.values, 0, ind.maxLeafN-ind.minLeafN);
				nEntries = (short) ind.minLeafN;
				newP.nEntries = (short) (ind.maxLeafN-ind.minLeafN);
				//New page and min key
				parent.addLeafPage(newP, newP.keys[0], this);
				if (newP.keys[0] >= key) {
					put(key, value);
				} else {
					newP.put(key, value);
				}
			}
		}

		//TODO rename to addPage
		//TODO remove 2nd parameter?!?
		void addLeafPage(AbstractIndexPage newP, long minKey, AbstractIndexPage prevPage) {
			if (isLeaf) {
				throw new JDOFatalDataStoreException();
			}
			if (nEntries < ind.maxInnerN) {
				//add page here
				//TODO use better search alg (only possible when searching keys i.o. page.
				//     However, I'm searching for PageID here, because we want to insert the new
				//     page right after the previous one. In case they have all the same values,
				//     it would be hard to insert the new page in the right position.
				//TODO what is the right position? Does it matter? Insertion ordered?
				//TODO Isn't prevPage always the first page with the value? 
				//     (Currently yes, see search alg)
				//TODO Should we store non-unique values more efficiently? Instead of always storing
				//     the key as well? -> Additional page type for values only? The local value only
				//     being a reference to the value page (inlc offs)? How would efficient insertion
				//     work (shifting values and references to value pages???) ?
				
				
				
				
				//Important!
				//TODO if this is the OIDindex, then we can optimize page fill ratio by _not_ 
				//     copying half the data to the next page, but instead start a new empty page.
				//     Something similar (with value checking) could work for 'integer' indices, 
				//     (unique, or if all values are stored under the same key).
				//    -> For OID: additional bonus: The local leaf page needs no updates -> saves
				//    one page to write.
				//    -> Pages should not be completely filled in case of parallel transactions,
				//       OIDs may not be written in order! -> Alternative: control values! If 
				//       consecutive, no insertions will occur! ->  (last-first)=nMaxEntries
				
				//For now, we assume a unique index.
				//TODO more efficient search
				int i;
				for ( i = 0; i < nEntries; i++ ) {
					if (keys[i] > minKey) {
						break;
					}
				}
				markPageDirty();
				System.arraycopy(keys, i, keys, i+1, nEntries-i);
				System.arraycopy(leaves, i+1, leaves, i+2, nEntries-i);
				System.arraycopy(leafPages, i+1, leafPages, i+2, nEntries-i);
				keys[i] = minKey;
				leaves[i+1] = newP;
				newP.parent = this;
				leafPages[i+1] = 0;
				nEntries++;
				return;
			} else {
				//treat page overflow
				ULLIndexPage newInner = (ULLIndexPage) ind.createPage(parent, false);
				
				//TODO use optimized fill ration for OIDS, just like above.
				markPageDirty();
				System.arraycopy(keys, ind.minInnerN+1, newInner.keys, 0, nEntries-ind.minInnerN-1);
				System.arraycopy(leaves, ind.minInnerN+1, newInner.leaves, 0, nEntries-ind.minInnerN);
				System.arraycopy(leafPages, ind.minInnerN+1, newInner.leafPages, 0, nEntries-ind.minInnerN);
				newInner.nEntries = (short) (nEntries-ind.minInnerN-1);
				newInner.assignThisAsRootToLeaves();

				if (parent == null) {
					ULLIndexPage newRoot = (ULLIndexPage) ind.createPage(null, false);
					newRoot.leaves[0] = this;
					newRoot.leaves[1] = newInner;
					newRoot.keys[0] = keys[ind.minInnerN];
					newRoot.nEntries = 1;
					this.parent = newRoot;
					newInner.parent = newRoot;
					ind.updateRoot(newRoot);
				} else {
					parent.addLeafPage(newInner, keys[ind.minInnerN], this);
				}
				nEntries = (short) (ind.minInnerN);
				//finally add the leaf to the according page
				if (minKey < newInner.keys[0]) {
					addLeafPage(newP, minKey, prevPage);
				} else {
					newInner.addLeafPage(newP, minKey, prevPage);
				}
				return;
			}
		}
		
		public void print() {
			if (isLeaf) {
				System.out.println("Leaf page(" + pageId() + "): n=" + nEntries + " oids=" + 
						Arrays.toString(keys));
			} else {
				System.out.println("Inner page(" + pageId() + "): n=" + nEntries + " oids=" + 
						Arrays.toString(keys));
				System.out.println("                " + nEntries + " page=" + 
						Arrays.toString(leafPages));
				System.out.print("[");
				for (int i = 0; i <= nEntries; i++) {
					if (leaves[i] != null) { 
						System.out.print("i=" + i + ": ");
						leaves[i].print();
					}
					else System.out.println("Page not loaded: " + leafPages[i]);
				}
				System.out.println("]");
			}
		}

		public void printLocal() {
			if (isLeaf) {
				System.out.println("Leaf page(" + pageId() + "): n=" + nEntries + " oids=" + 
						Arrays.toString(keys));
			} else {
				System.out.println("Inner page(" + pageId() + "): n=" + nEntries + " oids=" + 
						Arrays.toString(keys));
				System.out.println("                      " + Arrays.toString(leafPages));
			}
		}

		protected boolean remove(long oid) {
			if (!ind.isUnique()) {
				throw new IllegalStateException();
			}
			return remove(oid, 0);
		}
		
		protected boolean remove(long oid, long value) {
			//TODO use binary search(?)
			for ( int i = 0; i < nEntries; i++ ) {
				if (keys[i] > oid) {
					return false;
				}
				if (!ind.isUnique()) {
					if (values[i]!=value) {
						//TODO cover possibility of key on following page.
						continue;
					}
				}
				if (keys[i] == oid) {
					// first remove the element
					markPageDirty();
					System.arraycopy(keys, i+1, keys, i, nEntries-i-1);
					System.arraycopy(values, i+1, values, i, nEntries-i-1);
					nEntries--;
					if (nEntries == 0) {
						ind.statNLeaves--;
						parent.removeLeafPage(this);
					} else if (nEntries < (ind.maxLeafN >> 1) && (nEntries % 8 == 0)) {
						//The second term prevents frequent reading of previous and following pages.
						
						//now attempt merging this page
						ULLIndexPage prevPage = (ULLIndexPage) parent.getPrevLeafPage(this);
						if (prevPage != null) {
							//We merge only if they all fit on a single page. This means we may read
							//the previous page unnecessarily, but we avoid writing it as long as 
							//possible. TODO find a balance, and do no read prev page in all cases
							if (nEntries + prevPage.nEntries < ind.maxLeafN) {
								//TODO for now this work only for leaves with the same root. We
								//would need to update the min values in the inner nodes.
								prevPage.markPageDirty();
								System.arraycopy(keys, 0, prevPage.keys, prevPage.nEntries, nEntries);
								System.arraycopy(values, 0, prevPage.values, prevPage.nEntries, nEntries);
								prevPage.nEntries += nEntries;
								ind.statNLeaves--;
								parent.removeLeafPage(this);
							}
						}
					}
					return true;
				}
			}
			System.out.println("Key not found in page: " + oid);
			print();
			parent.print();
			throw new JDOFatalDataStoreException();
		}


		@Override
		protected void removeLeafPage(AbstractIndexPage indexPage) {
			for (int i = 0; i <= nEntries; i++) {
				if (leaves[i] == indexPage) {
					if (nEntries > 0) { //otherwise we just delete this page
						markPageDirty();
						if (i < nEntries) {  //otherwise it's the last element
							if (i > 0) {
								System.arraycopy(keys, i, keys, i-1, nEntries-i);
							} else {
								System.arraycopy(keys, 1, keys, 0, nEntries-1);
							}
							System.arraycopy(leaves, i+1, leaves, i, nEntries-i);
							System.arraycopy(leafPages, i+1, leafPages, i, nEntries-i);
						}
						leafPages[nEntries] = 0;
						leaves[nEntries] = null;
						nEntries--;
					
						//Now try merging
						if (parent == null) {
							return;
						}
						ULLIndexPage prev = (ULLIndexPage) parent.getPrevLeafPage(this);
						if (prev != null) {
							//TODO this is only good for merging inside the same root.
							if ((nEntries % 2 == 0) && (prev.nEntries + nEntries < ind.maxInnerN)) {
								System.arraycopy(keys, 0, prev.keys, prev.nEntries+1, nEntries);
								System.arraycopy(leaves, 0, prev.leaves, prev.nEntries+1, nEntries+1);
								System.arraycopy(leafPages, 0, prev.leafPages, prev.nEntries+1, nEntries+1);
								//find key -> go up or go down????? Up!
								int pos = parent.getPagePosition(this)-1;
								prev.keys[prev.nEntries] = ((ULLIndexPage)parent).keys[pos]; 
								prev.nEntries += nEntries + 1;  //for the additional key
								prev.assignThisAsRootToLeaves();
								ind.statNInner--;
								parent.removeLeafPage(this);
							}
							return;
						}
					} else if (parent != null) {
						ind.statNInner--;
						parent.removeLeafPage(this);
						nEntries--;
					} else {
						//No root and this is a leaf page... -> we do nothing.
						leafPages[0] = 0;
						leaves[0] = null;
						nEntries = 0;
					}
					return;
				}
			}
			System.out.println("this:" + parent);
			this.printLocal();
			System.out.println("leaf:");
			indexPage.printLocal();
			throw new JDOFatalDataStoreException("leaf page not found.");
		}

		public long getMax() {
			if (isLeaf) {
				if (nEntries == 0) {
					return Long.MIN_VALUE;
				}
				return keys[nEntries-1];
			}
			//handle empty indices
			if (nEntries == 0 && leaves[nEntries] == null && leafPages[nEntries] == 0) {
				return Long.MIN_VALUE;
			}
			long max = ((ULLIndexPage)getPageByPos(nEntries)).getMax();
			return max;
		}

		@Override
		void writeKeys() {
			ind._raf.writeShort(nEntries);
			for (long l: keys) {
				ind._raf.writeLong(l);
			}
		}

		@Override
		void readKeys() {
			nEntries = ind._raf.readShort();
			for (int i = 0; i < keys.length; i++) {
				keys[i] = ind._raf.readLong();
			}
		}

		@Override
		protected AbstractIndexPage newInstance() {
			return new ULLIndexPage(this);
		}
	}
	
	private transient ULLIndexPage root;
	
	/**
	 * Constructor for creating new index. 
	 * @param raf
	 */
	public PagedUniqueLongLong(PageAccessFile raf) {
		super(raf, true, 8, 8, true);
		System.out.println("OidIndex entries per page: " + maxLeafN + " / inner: " + maxInnerN);
		//bootstrap index
		root = createPage(null, false);
	}

	/**
	 * Constructor for reading index from disk.
	 */
	public PagedUniqueLongLong(PageAccessFile raf, int pageId) {
		super(raf, true, 8, 8, true);
		root = (ULLIndexPage) readRoot(pageId);
	}

	public void addLong(long key, long value) {
		ULLIndexPage page = getRoot().locatePageForKey(key, true);
		page.put(key, value);
	}

	public boolean removeLong(long key) {
		ULLIndexPage page = getRoot().locatePageForKey(key, false);
		if (page == null) {
			return false;
		}
		return page.remove(key);
	}

	public LLEntry findValue(long key) {
		ULLIndexPage page = getRoot().locatePageForKey(key, false);
		if (page == null) {
			return null;
		}
		return page.getValueFromLeaf(key);
	}

	@Override
	ULLIndexPage createPage(AbstractIndexPage parent, boolean isLeaf) {
		return new ULLIndexPage(this, parent, isLeaf);
	}

	@Override
	protected ULLIndexPage getRoot() {
		return root;
	}

	public Iterator<LLEntry> iterator(long min, long max) {
		return new ULLIterator(this, min, max);
	}

	@Override
	protected void updateRoot(AbstractIndexPage newRoot) {
		root = (ULLIndexPage) newRoot;
	}
	
	public void print() {
		root.print();
	}

	public long getMaxValue() {
		return root.getMax();
	}

	public Iterator<LLEntry> descendingIterator(long max, long min) {
		return new ULLDescendingIterator(this, max, min);
	}

}
