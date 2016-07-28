/*
 * ao-web-page-servlet - Java API for modeling web page content and relationships in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-web-page-servlet.
 *
 * ao-web-page-servlet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-web-page-servlet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-web-page-servlet.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.web.page.servlet.impl;

import com.aoindustries.io.buffer.BufferResult;
import com.aoindustries.servlet.http.Dispatcher;
import com.aoindustries.web.page.Node;
import com.aoindustries.web.page.Page;
import com.aoindustries.web.page.servlet.CaptureLevel;
import com.aoindustries.web.page.servlet.CapturePage;
import com.aoindustries.web.page.servlet.CurrentNode;
import com.aoindustries.web.page.servlet.CurrentPage;
import com.aoindustries.web.page.servlet.PageRefResolver;
import java.io.IOException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.SkipPageException;

final public class PageImpl {

	public static interface PageImplBody<E extends Throwable> {
		BufferResult doBody(boolean discard, Page page) throws E, IOException, SkipPageException;
	}

	public static <E extends Throwable> void doPageImpl(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String title,
		Boolean toc,
		int tocLevels,
		PageImplBody<E> body
	) throws E, ServletException, IOException, SkipPageException {
		final Page page = new Page();
		// Find the path to this page
		page.setPageRef(PageRefResolver.getCurrentPageRef(servletContext, request));

		{
			// Pages may not be nested within any kind of node
			Node parentNode = CurrentNode.getCurrentNode(request);
			if(parentNode != null) throw new ServletException("Pages may not be nested within other nodes: " + page.getPageRef() + " not allowed inside of " + parentNode);
			assert CurrentPage.getCurrentPage(request) == null : "When no parent node, cannot have a parent page";
		}

		page.setTitle(title);
		page.setToc(toc);
		page.setTocLevels(tocLevels);

		// Set currentNode
		CurrentNode.setCurrentNode(request, page);
		try {
			// Set currentPage
			CurrentPage.setCurrentPage(request, page);
			try {
				// Freeze page once body done
				try {
					// Unlike elements, the page body is still invoked on captureLevel=PAGE, this
					// is done to catch childen.
					if(body != null) {
						final CaptureLevel captureLevel = CaptureLevel.getCaptureLevel(request);
						if(captureLevel == CaptureLevel.BODY) {
							// Invoke page body, capturing output
							page.setBody(body.doBody(false, page).trim());
						} else {
							// Invoke page body, discarding output
							body.doBody(true, page);
						}
					}
				} finally {
					page.freeze();
				}
			} finally {
				// Restore previous currentPage
				CurrentPage.setCurrentPage(request, null);
			}
		} finally {
			// Restore previous currentNode
			CurrentNode.setCurrentNode(request, null);
		}
		CapturePage capture = CapturePage.getCaptureContext(request);
		if(capture != null) {
			// Capturing, add to capture
			capture.setCapturedPage(page);
		} else {
			// Display page directly
			// Forward to PAGE_TEMPLATE_JSP_PATH, passing PAGE_REQUEST_ATTRIBUTE request attribute
			Object oldValue = request.getAttribute(com.aoindustries.web.page.servlet.Page.PAGE_REQUEST_ATTRIBUTE);
			try {
				// Pass PAGE_REQUEST_ATTRIBUTE attribute
				request.setAttribute(com.aoindustries.web.page.servlet.Page.PAGE_REQUEST_ATTRIBUTE, page);
				Dispatcher.forward(
					servletContext,
					com.aoindustries.web.page.servlet.Page.PAGE_TEMPLATE_JSP_PATH,
					request,
					response
				);
			} finally {
				// Restore old value of PAGE_REQUEST_ATTRIBUTE attribute
				request.setAttribute(com.aoindustries.web.page.servlet.Page.PAGE_REQUEST_ATTRIBUTE, oldValue);
			}
			throw new SkipPageException();
		}
	}

	/**
	 * Make no instances.
	 */
	private PageImpl() {
	}
}