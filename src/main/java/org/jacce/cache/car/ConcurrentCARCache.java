package org.jacce.cache.car;


import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jacce.Cache;

/**
 * "CLOCK with Adaptive Replacement" cache implementation.
 * Thread-safe. Synchronization is done on 'this' reference.
 * 
 * TODO: constant size buffer for t1,t2,b1,b2
 */
public class ConcurrentCARCache extends Cache {
	private static final org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(ConcurrentCARCache.class.getName());
	
	
	private final float LOAD_FACTOR = 0.75f; 
	
	private final int capacity2;
	private int p;
	
	private Map<Object, CARItem> t1map, t2map;
	
	private List<Object> t1, t2, b1, b2;
	
	private int t1hand, t2hand;

	
	public ConcurrentCARCache(String id, String description, int capacity, int memorySize) {
		super(id, description, capacity, memorySize);
		capacity2 = capacity*2;
		init();
	}
	
	
	private void init() {
		p = 0;
		t1hand = -1;
		t2hand = -1;
		
		t1map = new HashMap<Object, CARItem>((int)Math.ceil(capacity/LOAD_FACTOR)+1);
		t2map = new HashMap<Object, CARItem>((int)Math.ceil(capacity/LOAD_FACTOR)+1);
		
		t1 = new LinkedList<Object>();
		t2 = new LinkedList<Object>();
		b1 = new LinkedList<Object>();		// LRU is [0], MRU is [size]
		b2 = new LinkedList<Object>();
	}
	
	
	@Override
	public void set(Object id, Object entry) {
		synchronized(this) {
			
			// if the entry already exist in cache then update its value
			CARItem ci = t1map.get(id);
			if (ci != null) {
				ci.setValue(entry);
			} else {
				ci = t2map.get(id);
				if (ci != null)
					ci.setValue(entry);
			}
			
			if (ci == null) {
				int b1Index = b1.indexOf(id);
				int b2Index = b2.indexOf(id);
				
				if (t1.size() + t2.size() == capacity) { 
					logger.debug("T1+T2 = c replacing for : " + id);
					
					// cache full, replace a page from cache
					replace();
					
					// cache directory replacement
					if (b1Index == -1 && b2Index == -1) {
						if (t1.size() + b1.size() == capacity) {
							// Discard the LRU page in B1
							logger.debug("T1+B1 < c discarding : " + b1.get(0));
							b1.remove(0);
						} else if (t1.size() + t2.size() + b1.size() + b2.size() == capacity2) {
							//Discard the LRU page in B2.
							logger.debug("T1+B1+T2+B2 < 2c discarding :" + b2.get(0));
							b2.remove(0);
						}
					}
				}
	
				// cache directory miss
				if (b1Index == -1 && b2Index == -1) {
					logger.debug("insert into T1 tail : " + id);
				
					//Insert x at the tail of T1. Set the page reference bit of x to 0.
					putT1Tail(new CARItem(id, entry));
				} else if (b1Index != -1) {
					logger.debug("page in B1, inserting to T2 tail : " + id);
					
					//Adapt: Increase the target size for the list T1 as: p = min {p + max{1, |B2|/|B1|}, c}
					p = Math.min(p + Math.max(1, b2.size()/b1.size()), capacity);
					
				    //Move x at the tail of T2. Set the page reference bit of x to 0.
					putT2Tail(new CARItem(id, entry));
					b1.remove(b1Index);
				} else {
					logger.debug("page in B2, inserting to T2 tail : " + id);
					
					//Adapt: Decrease the target size for the list T1 as: p = max {p − max{1, |B1|/|B2|}, 0}
					p = Math.max(p - Math.max(1, b1.size()/b2.size()), 0);
					
				    //Move x at the tail of T2. Set the page reference bit of x to 0.
					putT2Tail(new CARItem(id, entry));
					b2.remove(b2Index);
				}
			
			}
		}
	}
	

	@Override
	public Object get(Object id) {
		requests++;

		CARItem ci = t1map.get(id);
		
		if (ci != null) {
			ci.referenceBit = true;   // set page reference bit
			logger.debug("get from T1: " + id);
			hits++;
			return ci.value;
		} 

		ci = t2map.get(id);
		
		if (ci != null) {
			ci.referenceBit = true;   // set page reference bit
			logger.debug("get from T2: " + id);
			hits++;
			return ci.value;
		}

		return null;
	}
	
	@Override
	public void invalidate(Object id) {
		logger.debug("invalidating: " + id);
		
		synchronized(this) {
			if (t1map.containsKey(id)) {
				t1map.remove(id);
				for (int i = 0; i < t1.size(); i++) {
					if (t1.get(i).equals(id)) {
						// evict into B1
						b1.add(t1.get(i));
						logger.debug("removing from T1: " + t1.get(i));
						t1.remove(i);
						
						if (i <= t1hand) {
							t1hand--;
							if (t1hand == -1)
								t1hand = t1.size()-1;
						}
						
						i--;
					}
				}
			}
		}
		
		synchronized(this) {
			if (t2map.containsKey(id)) {
				t2map.remove(id);
				for (int i = 0; i < t2.size(); i++) {
					if (t2.get(i).equals(id)) {
						// evict into B1
						b2.add(t2.get(i));
						logger.debug("removing from T2: " + t2.get(i));
						t2.remove(i);
						
						if (i <= t2hand) {
							t2hand--;
							if (t2hand == -1)
								t2hand = t2.size()-1;
						}
						
						i--;
					}
				}
			}
		}
	}

	@Override
	public void invalidateGroup(Set<Object> ids) {
		logger.debug("invalidating: " + ids);
		
		synchronized(this) {
			for (int i = 0; i < t1.size(); i++) {
				if (ids.contains(t1.get(i))) {
					// evict into B1
					logger.debug("removing from T1: " + t1.get(i));
					b1.add(t1.get(i));
					t1map.remove(t1.get(i));
					t1.remove(i);
					if (i <= t1hand) {
						t1hand--;
						if (t1hand == -1)
							t1hand = t1.size()-1;
					}
					
					i--;
				}
			}
		}
		
		synchronized(this) {
			for (int i = 0; i < t2.size(); i++) {
				if (ids.contains(t2.get(i))) {
					// evict into B2
					logger.debug("removing from T2: " + t2.get(i));
					b2.add(t2.get(i));
					t2map.remove(t2.get(i));
					t2.remove(i);
					if (i <= t2hand) {
						t2hand--;
						if (t2hand == -1)
							t2hand = t2.size()-1;
					}
					
					i--;
				}
			}
		}
	}
	
	
	private void replace() {
		int allowedPages = Math.max(1, p);
		
		while (true) {
			
			if (t1.size() >= allowedPages) {

				// the page reference bit of head page in T1 is 0
				if (!getT1Head().referenceBit) {
					logger.debug("REPLACE(), moving T1 head to B1 MRU : " + getT1Head().id);
					
					// Demote the head page in T1 and make it the MRU page in B1.
					b1.add(getT1Head().id);
					removeT1Head();

					break;
				} else {
					logger.debug("REPLACE(), moving T1 head to T2 tail : " + getT1Head().id);
					
					//Set the page reference bit of head page in T1 to 0, and make it the tail page in T2.
					getT1Head().referenceBit = false;
					putT2Tail(getT1Head());
					removeT1Head();
				}
			} else {
				if (t2hand == -1)					// in case T2 is empty
					break;
				
				// the page reference bit of head page in T2 is 0
				if (!getT2Head().referenceBit) {
					logger.debug(">>>>>>>>> REPLACE() >>> moving T2 head to B2 MRU : " + getT2Head().id);
					
					// Demote the head page in T2 and make it the MRU page in B2
					b2.add(getT2Head().id);
					removeT2Head();

					break;
				} else {
					logger.debug(">>>>>>>>> REPLACE() >>> moving T2 head to T2 tail : " + getT2Head().id);
					
					//Set the page reference bit of head page in T2 to 0, and make it the tail page in T2.
					getT2Head().referenceBit = false;
					t2hand++;        
					if (t2hand >= t2.size())         // this is a circular buffer, adjust head appropriatly
						t2hand = 0;
				}
			}
		}
	}
	
	
	private void removeT1Head() {
		t1map.remove(t1.remove(t1hand--));
	}
	
	private void removeT2Head() {
		t2map.remove(t2.remove(t2hand--));
	}

	private CARItem getT1Head() {
		return (t1hand == -1)?null:t1map.get(t1.get(t1hand));
	}
	
	private CARItem getT2Head() {
		return (t2hand == -1)?null:t2map.get(t2.get(t2hand));
	}
	
	private void putT1Tail(CARItem co) {
		t1map.put(co.id, co);
		
		if (t1hand == -1)		
			t1.add(co.id);
		else 
			t1.add(t1hand, co.id);

		t1hand++;
	}
	
	private void putT2Tail(CARItem co) {
		t2map.put(co.id, co);
		
		if (t2hand == -1)
			t2.add(co.id);
		else
			t2.add(t2hand, co.id);

		t2hand++;
	}
}