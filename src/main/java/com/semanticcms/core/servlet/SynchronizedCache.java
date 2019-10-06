/*
 * semanticcms-core-servlet - Java API for modeling web page content and relationships in a Servlet environment.
 * Copyright (C) 2016  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of semanticcms-core-servlet.
 *
 * semanticcms-core-servlet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * semanticcms-core-servlet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with semanticcms-core-servlet.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.semanticcms.core.servlet;

import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import static com.semanticcms.core.servlet.Cache.VERIFY_CACHE_PARENT_CHILD_RELATIONSHIPS;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletException;

/**
 * A page cache that is thread safe using synchronization on the default HashMaps.
 */
class SynchronizedCache extends MapCache {

	SynchronizedCache() {
		super(
			new HashMap<CaptureKey,Page>(),
			VERIFY_CACHE_PARENT_CHILD_RELATIONSHIPS ? new HashMap<PageRef,Set<PageRef>>() : null,
			VERIFY_CACHE_PARENT_CHILD_RELATIONSHIPS ? new HashMap<PageRef,Set<PageRef>>() : null,
			new HashMap<String, Object>()
		);
	}

	@Override
	synchronized Page get(CaptureKey key) {
		return super.get(key);
	}

	@Override
	synchronized void put(CaptureKey key, Page page) throws ServletException {
		super.put(key, page);
	}

	@Override
	protected void verifyAdded(Page page) throws ServletException {
		assert Thread.holdsLock(this);
		super.verifyAdded(page);
	}

	@Override
	public <K,V> Map<K,V> newMap() {
		return Collections.synchronizedMap(new HashMap<K,V>());
	}

	@Override
	public <K,V> Map<K,V> newMap(int size) {
		return Collections.synchronizedMap(new HashMap<K,V>(size *4/3+1));
	}

	@Override
	public void setAttribute(String key, Object value) {
		synchronized(attributes) {
			super.setAttribute(key, value);
		}
	}

	@Override
	public Object getAttribute(String key) {
		synchronized(attributes) {
			return super.getAttribute(key);
		}
	}

	@Override
	public <V, E extends Exception> V getAttribute(String key, Class<V> clazz, Callable<? extends V, E> callable) throws E {
		synchronized(attributes) {
			return super.getAttribute(key, clazz, callable);
		}
	}

	@Override
	public void removeAttribute(String key) {
		synchronized(attributes) {
			super.removeAttribute(key);
		}
	}
}