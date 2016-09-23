/*
 * semanticcms-core-servlet - Java API for modeling web page content and relationships in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016  AO Industries, Inc.
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

import com.aoindustries.io.TempFileList;
import com.aoindustries.lang.NullArgumentException;
import com.aoindustries.servlet.filter.TempFileContext;
import com.aoindustries.servlet.http.Dispatcher;
import com.aoindustries.servlet.http.NullHttpServletResponseWrapper;
import com.aoindustries.servlet.http.ServletUtil;
import com.semanticcms.core.model.Node;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.servlet.util.HttpServletSubRequest;
import com.semanticcms.core.servlet.util.HttpServletSubResponse;
import com.semanticcms.core.servlet.util.ThreadSafeHttpServletRequest;
import com.semanticcms.core.servlet.util.ThreadSafeHttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.SkipPageException;

public class CapturePage {

	private static final String CAPTURE_CONTEXT_REQUEST_ATTRIBUTE_NAME = CapturePage.class.getName()+".captureContext";

	/**
	 * Gets the capture context or <code>null</code> if none occurring.
	 */
	public static CapturePage getCaptureContext(ServletRequest request) {
		return (CapturePage)request.getAttribute(CAPTURE_CONTEXT_REQUEST_ATTRIBUTE_NAME);
	}

	/**
	 * Captures a page.
	 * The capture is always done with a request method of "GET", even when the enclosing request is a different method.
	 * Also validates parent-child and child-parent relationships if the other related pages happened to already be captured and cached.
	 *
	 * TODO: Within the scope of one overall request, avoid capturing the same page at the same time (CurrencyLimiter applied to sub requests), is there a reasonable way to catch deadlock conditions?
	 *
	 * @param level  The minimum page capture level, note that a higher level might be substituted, such as a META capture in place of a PAGE request.
	 */
	public static Page capturePage(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		PageRef pageRef,
		CaptureLevel level
	) throws ServletException, IOException {
		return capturePage(
			servletContext,
			request,
			response,
			pageRef,
			level,
			CapturePageCacheFilter.getCache(request)
		);
	}

	private static Page capturePage(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		PageRef pageRef,
		CaptureLevel level,
		PageCache cache
	) throws ServletException, IOException {
		NullArgumentException.checkNotNull(level, "level");

		// Don't use cache for full body captures
		boolean useCache = level != CaptureLevel.BODY;

		// cacheKey will be null when this capture is not to be cached
		final PageCache.Key cacheKey;
		Page capturedPage;
		if(useCache) {
			// Check the cache
			cacheKey = new PageCache.Key(pageRef, level);
			synchronized(cache) {
				capturedPage = cache.get(cacheKey);
				if(capturedPage == null && level == CaptureLevel.PAGE) {
					// Look for meta in place of page
					capturedPage = cache.get(pageRef, CaptureLevel.META);
				}
				// Set useCache = false to not put back into the cache unnecessarily below
				useCache = capturedPage == null;
			}
		} else {
			cacheKey = null;
			capturedPage = null;
		}

		if(capturedPage == null) {
			// Perform new capture
			Node oldNode = CurrentNode.getCurrentNode(request);
			Page oldPage = CurrentPage.getCurrentPage(request);
			try {
				// Clear request values that break captures
				if(oldNode != null) CurrentNode.setCurrentNode(request, null);
				if(oldPage != null) CurrentPage.setCurrentPage(request, null);
				CaptureLevel oldCaptureLevel = CaptureLevel.getCaptureLevel(request);
				CapturePage oldCaptureContext = CapturePage.getCaptureContext(request);
				try {
					// Set new capture context
					CaptureLevel.setCaptureLevel(request, level);
					CapturePage captureContext = new CapturePage();
					request.setAttribute(CAPTURE_CONTEXT_REQUEST_ATTRIBUTE_NAME, captureContext);
					// Include the page resource, discarding any direct output
					final String capturePath = pageRef.getServletPath();
					try {
						// Clear PageContext on include
						PageContext.newPageContextSkip(
							null,
							null,
							null,
							new PageContext.PageContextCallableSkip() {
								@Override
								public void call() throws ServletException, IOException, SkipPageException {
									Dispatcher.include(
										servletContext,
										capturePath,
										// Always capture as "GET" request
										ServletUtil.METHOD_GET.equals(request.getMethod())
											// Is already "GET"
											? request
											// Wrap to make "GET"
											: new HttpServletRequestWrapper(request) {
												@Override
												public String getMethod() {
													return ServletUtil.METHOD_GET;
												}
											},
										new NullHttpServletResponseWrapper(response)
									);
								}
							}
						);
					} catch(SkipPageException e) {
						// An individual page may throw SkipPageException which only terminates
						// the capture, not the request overall
					}
					capturedPage = captureContext.getCapturedPage();
					if(capturedPage==null) throw new ServletException("No page captured, page=" + capturePath);
					PageRef capturedPageRef = capturedPage.getPageRef();
					if(!capturedPageRef.equals(pageRef)) throw new ServletException(
						"Captured page has unexpected pageRef.  Expected "
							+ pageRef
							+ " but got "
							+ capturedPageRef
					);
				} finally {
					// Restore previous capture context
					CaptureLevel.setCaptureLevel(request, oldCaptureLevel);
					request.setAttribute(CAPTURE_CONTEXT_REQUEST_ATTRIBUTE_NAME, oldCaptureContext);
				}
			} finally {
				if(oldNode != null) CurrentNode.setCurrentNode(request, oldNode);
				if(oldPage != null) CurrentPage.setCurrentPage(request, oldPage);
			}
		}
		assert capturedPage != null;
		synchronized(cache) {
			if(useCache) {
				// Add to cache
				cache.put(cacheKey, capturedPage);
			}
			// Verify parents that happened to already be cached
			if(!capturedPage.getAllowParentMismatch()) {
				for(PageRef parentRef : capturedPage.getParentPages()) {
					// Can't verify parent reference to missing book
					if(parentRef.getBook() != null) {
						// Check if parent in cache
						Page parentPage = cache.get(parentRef, CaptureLevel.PAGE);
						if(parentPage == null) parentPage = cache.get(parentRef, CaptureLevel.META);
						if(parentPage != null) {
							if(!parentPage.getChildPages().contains(pageRef)) {
								throw new ServletException(
									"The parent page does not have this as a child.  this="
										+ pageRef
										+ ", parent="
										+ parentRef
								);
							}
							// Verify parent's children that happened to already be cached since captures can happen in any order.
							/*
							if(!parentPage.getAllowChildMismatch()) {
								for(PageRef childRef : parentPage.getChildPages()) {
									// Can't verify child reference to missing book
									if(childRef.getBook() != null) {
										// Check if child in cache
										Page childPage = cache.get(new CapturePageCacheKey(childRef, CaptureLevel.PAGE));
										if(childPage == null) childPage = cache.get(new CapturePageCacheKey(childRef, CaptureLevel.META));
										if(childPage != null) {
											if(!childPage.getParentPages().contains(parentRef)) {
												throw new ServletException(
													"The child page does not have this as a parent.  this="
														+ parentRef
														+ ", child="
														+ childRef
												);
											}
										}
									}
								}
							}
							 */
						}
					}
				}
			}
			// Verify children that happened to already be cached
			if(!capturedPage.getAllowChildMismatch()) {
				for(PageRef childRef : capturedPage.getChildPages()) {
					// Can't verify child reference to missing book
					if(childRef.getBook() != null) {
						// Check if child in cache
						Page childPage = cache.get(childRef, CaptureLevel.PAGE);
						if(childPage == null) childPage = cache.get(childRef, CaptureLevel.META);
						if(childPage != null) {
							if(!childPage.getParentPages().contains(pageRef)) {
								throw new ServletException(
									"The child page does not have this as a parent.  this="
										+ pageRef
										+ ", child="
										+ childRef
								);
							}
							// Verify children's parents that happened to already be cached since captures can happen in any order.
							/*
							if(!childPage.getAllowParentMismatch()) {
								for(PageRef parentRef : childPage.getParentPages()) {
									// Can't verify parent reference to missing book
									if(parentRef.getBook() != null) {
										// Check if parent in cache
										Page parentPage = cache.get(new CapturePageCacheKey(parentRef, CaptureLevel.PAGE));
										if(parentPage == null) parentPage = cache.get(new CapturePageCacheKey(parentRef, CaptureLevel.META));
										if(parentPage != null) {
											if(!parentPage.getChildPages().contains(childRef)) {
												throw new ServletException(
													"The parent page does not have this as a child.  this="
														+ childRef
														+ ", parent="
														+ parentRef
												);
											}
										}
									}
								}
							}*/
						}
					}
				}
			}
		}
		return capturedPage;
	}

	/**
	 * Captures a page in the current page context.
	 *
	 * @see  #capturePage(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, com.semanticcms.core.model.PageRef, com.semanticcms.core.servlet.CaptureLevel)
	 * @see  PageContext
	 */
	public static Page capturePage(
		PageRef pageRef,
		CaptureLevel level
	) throws ServletException, IOException {
		return capturePage(
			PageContext.getServletContext(),
			PageContext.getRequest(),
			PageContext.getResponse(),
			pageRef,
			level
		);
	}

	/**
	 * Captures multiple pages.
	 *
	 * @param  pageRefs  The pages that should be captured.  This set will be iterated only once during this operation.
	 *
	 * @return  map from pageRef to page, with iteration order equal to the pageRefs set.
	 *
	 * @see  #capturePage(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, com.semanticcms.core.model.PageRef, com.semanticcms.core.servlet.CaptureLevel)
	 */
	public static Map<PageRef,Page> capturePages(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Set<? extends PageRef> pageRefs,
		final CaptureLevel level
	) throws ServletException, IOException {
		int size = pageRefs.size();
		if(size == 0) {
			return Collections.emptyMap();
		} else if(size == 1) {
			PageRef pageRef = pageRefs.iterator().next();
			return Collections.singletonMap(
				pageRef,
				capturePage(servletContext, request, response, pageRef, level)
			);
		} else {
			final PageCache cache = CapturePageCacheFilter.getCache(request);
			Map<PageRef,Page> results = new LinkedHashMap<PageRef,Page>(size * 4/3 + 1);
			List<PageRef> notCachedList = new ArrayList<PageRef>(size);
			if(level != CaptureLevel.BODY) {
				// Check cache before queuing on different threads, building list of those not in cache
				synchronized(cache) {
					for(PageRef pageRef : pageRefs) {
						Page page = cache.get(pageRef, level);
						if(page == null && level == CaptureLevel.PAGE) {
							// Look for meta in place of page
							page = cache.get(pageRef, CaptureLevel.META);
						}
						if(page != null) {
							// Use cached value
							results.put(pageRef, page);
						} else {
							// Will capture below
							notCachedList.add(pageRef);
						}
					}
				}
			} else {
				notCachedList.addAll(pageRefs);
			}

			SemanticCMS semanticCMS;
			int notCachedSize = notCachedList.size();
			if(
				notCachedSize > 1
				&& (
					semanticCMS = SemanticCMS.getInstance(servletContext)
				).useConcurrentSubrequests(request)
			) {
				// Concurrent implementation
				TempFileList tempFileList = TempFileContext.getTempFileList(request);
				HttpServletRequest threadSafeReq = new ThreadSafeHttpServletRequest(request);
				HttpServletResponse threadSafeResp = new ThreadSafeHttpServletResponse(response);
				// Create the tasks
				List<Callable<Page>> tasks = new ArrayList<Callable<Page>>(notCachedSize);
				for(int i=0; i<notCachedSize; i++) {
					final PageRef pageRef = notCachedList.get(i);
					final HttpServletRequest subrequest = new HttpServletSubRequest(threadSafeReq);
					final HttpServletResponse subresponse = new HttpServletSubResponse(threadSafeResp, tempFileList);
					tasks.add(
						new Callable<Page>() {
							@Override
							public Page call() throws ServletException, IOException {
								return capturePage(
									servletContext,
									subrequest,
									subresponse,
									pageRef,
									level,
									cache
								);
							}
						}
					);
				}
				List<Page> notCachedResults;
				try {
					notCachedResults = semanticCMS.getExecutors().getPerProcessor().callAll(tasks);
				} catch(InterruptedException e) {
					throw new ServletException(e);
				} catch(ExecutionException e) {
					Throwable cause = e.getCause();
					if(cause instanceof RuntimeException) throw (RuntimeException)cause;
					if(cause instanceof ServletException) throw (ServletException)cause;
					if(cause instanceof IOException) throw (IOException)cause;
					throw new ServletException(cause);
				}
				for(int i=0; i<notCachedSize; i++) {
					results.put(
						notCachedList.get(i),
						notCachedResults.get(i)
					);
				}
			} else {
				// Sequential implementation
				for(PageRef pageRef : notCachedList) {
					results.put(
						pageRef,
						capturePage(servletContext, request, response, pageRef, level, cache)
					);
				}
			}
			return Collections.unmodifiableMap(results);
		}
	}

	/**
	 * Captures multiple pages in the current page context.
	 *
	 * @see  #capturePages(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.Iterable, com.semanticcms.core.servlet.CaptureLevel)
	 * @see  PageContext
	 */
	public static Map<PageRef,Page> capturePages(
		Set<? extends PageRef> pageRefs,
		CaptureLevel level
	) throws ServletException, IOException {
		return capturePages(
			PageContext.getServletContext(),
			PageContext.getRequest(),
			PageContext.getResponse(),
			pageRefs,
			level
		);
	}

	public static interface TraversalEdges {
		/**
		 * Gets the child pages to consider for the given page during a traversal.
		 */
		Collection<PageRef> getEdges(Page page);
	}

	public static interface PageHandler {
		/**
		 * Called after page captured but before or after children captured.
		 */
		void handlePage(Page page) throws ServletException, IOException;
	}

	/**
	 * Performs a concurrent depth-first traversal of the pages.
	 * Each page is only visited once.
	 *
	 * @see  #traversePagesDepthFirst(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, com.semanticcms.core.model.Page, com.semanticcms.core.servlet.CaptureLevel, com.semanticcms.core.servlet.CapturePage.PageHandler, com.semanticcms.core.servlet.CapturePage.ChildPageFilter, com.semanticcms.core.servlet.CapturePage.PageHandler)  If have page, provide it
	 */
	public static void traversePagesDepthFirst(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		PageRef rootRef,
		CaptureLevel level,
		PageHandler preHandler,
		TraversalEdges edges,
		PageHandler postHandler
	) throws ServletException, IOException {
		traversePagesDepthFirst(
			servletContext,
			request,
			response,
			CapturePage.capturePage(
				servletContext,
				request,
				response,
				rootRef,
				level
			),
			level,
			preHandler,
			edges,
			postHandler
		);
	}

	/**
	 * Performs a concurrent depth-first traversal of the pages.
	 * Each page is only visited once.
	 */
	public static void traversePagesDepthFirst(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page root,
		CaptureLevel level,
		PageHandler preHandler,
		TraversalEdges edges,
		PageHandler postHandler
	) throws ServletException, IOException {
		traversePagesDepthFirstRecurse(
			servletContext,
			request,
			response,
			root,
			level,
			preHandler,
			edges,
			postHandler,
			SemanticCMS.getInstance(servletContext),
			TempFileContext.getTempFileList(request),
			level == CaptureLevel.BODY ? null : CapturePageCacheFilter.getCache(request),
			new HashSet<PageRef>()
		);
	}

	/**
	 * TODO: More advanced traversal as figured-out by Dan and Brian on the whiteboard.
	 */
	private static void traversePagesDepthFirstRecurse(
		final ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page page,
		final CaptureLevel level,
		PageHandler preHandler,
		TraversalEdges edges,
		PageHandler postHandler,
		SemanticCMS semanticCMS,
		TempFileList tempFileList,
		final PageCache cache,
		Set<PageRef> visited
	) throws ServletException, IOException {
		if(!visited.add(page.getPageRef())) throw new AssertionError();
		if(preHandler != null) preHandler.handlePage(page);
		List<PageRef> childRefs;
		{
			Collection<PageRef> childRefSet = edges.getEdges(page);
			childRefs = new ArrayList<PageRef>(childRefSet.size());
			for(PageRef childRef : childRefSet) {
				if(!visited.contains(childRef)) {
					childRefs.add(childRef);
				}
			}
		}
		int childRefsSize = childRefs.size();
		if(childRefsSize > 0) {
			List<Page> childPageList = new ArrayList<Page>(childRefsSize);
			{
				List<Integer> notCachedIndexes = new ArrayList<Integer>(childRefsSize);
				List<PageRef> notCachedRefs;
				if(level != CaptureLevel.BODY) {
					notCachedRefs = new ArrayList<PageRef>(childRefsSize);
					synchronized(cache) {
						for(int i=0; i<childRefsSize; i++) {
							PageRef childRef = childRefs.get(i);
							Page cached = cache.get(childRef, level);
							if(cached == null && level == CaptureLevel.PAGE) {
								// Look for meta in place of page
								cached = cache.get(childRef, CaptureLevel.META);
								if(cached != null) System.out.println("TODO: Got traversal meta in place of page: " + childRef);
							}
							if(cached != null) {
								childPageList.add(cached);
							} else {
								childPageList.add(null);
								notCachedIndexes.add(i);
								notCachedRefs.add(childRef);
							}
						}
					}
				} else {
					notCachedRefs = childRefs;
					for(int i=0; i<childRefsSize; i++) {
						childPageList.add(null);
						notCachedIndexes.add(i);
					}
				}
				int notCachedSize = notCachedIndexes.size();
				if(
					notCachedSize > 1
					&& semanticCMS.useConcurrentSubrequests(request) // TODO: Look this up once and pass as variable through recursion?
				) {
					// Concurrent implementation
					HttpServletRequest threadSafeReq = new ThreadSafeHttpServletRequest(request);
					HttpServletResponse threadSafeResp = new ThreadSafeHttpServletResponse(response);
					// Create the tasks
					List<Callable<Page>> tasks = new ArrayList<Callable<Page>>(notCachedSize);
					for(int i=0; i<notCachedSize; i++) {
						final PageRef notCachedRef = notCachedRefs.get(i);
						final HttpServletRequest subrequest = new HttpServletSubRequest(threadSafeReq);
						final HttpServletResponse subresponse = new HttpServletSubResponse(threadSafeResp, tempFileList);
						tasks.add(
							new Callable<Page>() {
								@Override
								public Page call() throws ServletException, IOException {
									return capturePage(
										servletContext,
										subrequest,
										subresponse,
										notCachedRef,
										level,
										cache
									);
								}
							}
						);
					}
					List<Page> notCachedResults;
					try {
						notCachedResults = semanticCMS.getExecutors().getPerProcessor().callAll(tasks);
					} catch(InterruptedException e) {
						throw new ServletException(e);
					} catch(ExecutionException e) {
						Throwable cause = e.getCause();
						if(cause instanceof RuntimeException) throw (RuntimeException)cause;
						if(cause instanceof ServletException) throw (ServletException)cause;
						if(cause instanceof IOException) throw (IOException)cause;
						throw new ServletException(cause);
					}
					for(int i=0; i<notCachedSize; i++) {
						childPageList.set(
							notCachedIndexes.get(i),
							notCachedResults.get(i)
						);
					}
				} else {
					// Sequential implementation
					for(int i=0; i<notCachedSize; i++) {
						childPageList.set(
							notCachedIndexes.get(i),
							CapturePage.capturePage(
								servletContext,
								request,
								response,
								notCachedRefs.get(i),
								level,
								cache
							)
						);
					}
				}
			}
			for(Page childPage : childPageList) {
				// May have been visited already during depth-first
				if(!visited.contains(childPage.getPageRef())) {
					traversePagesDepthFirstRecurse(
						servletContext,
						request,
						response,
						childPage,
						level,
						preHandler,
						edges,
						postHandler,
						semanticCMS,
						tempFileList,
						cache,
						visited
					);
				}
			}
		}
		if(postHandler != null) postHandler.handlePage(page);
	}

	private CapturePage() {
	}

	private Page capturedPage;
	public void setCapturedPage(Page capturedPage) {
		NullArgumentException.checkNotNull(capturedPage, "page");
		if(this.capturedPage != null) {
			throw new IllegalStateException(
				"Cannot capture more than one page: first page="
				+ this.capturedPage.getPageRef()
				+ ", second page=" + capturedPage.getPageRef()
			);
		}
		this.capturedPage = capturedPage;
	}

	private Page getCapturedPage() {
		return capturedPage;
	}
}
