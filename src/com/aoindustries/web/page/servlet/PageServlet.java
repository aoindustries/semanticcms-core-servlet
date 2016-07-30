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
package com.aoindustries.web.page.servlet;

import com.aoindustries.servlet.http.Includer;
import com.aoindustries.servlet.http.ServletUtil;
import com.aoindustries.web.page.Page;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.SkipPageException;

/**
 * Automatically sets up the Page and the PageContext.
 *
 * @see  Page
 * @see  PageContext
 */
abstract public class PageServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	/**
	 * @see  Page#getTitle()
	 */
	abstract public String getTitle();

	/**
	 * Defaults to null for "auto".
	 *
	 * @see  Page#getToc()
	 */
	public Boolean getToc() {
		return null;
	}

	/**
	 * @see  Page#getTocLevels()
	 * @see  Page#DEFAULT_TOC_LEVELS
	 */
	public int getTocLevels() {
		return Page.DEFAULT_TOC_LEVELS;
	}

	private static interface DoMethodCallable {
		void doMethod(Page page) throws ServletException, IOException, SkipPageException;
	}

	private void callInPage(HttpServletRequest req, HttpServletResponse resp, DoMethodCallable method) throws ServletException, IOException {
		try {
			new com.aoindustries.web.page.servlet.Page(getServletContext(), req, resp, getTitle())
				.toc(getToc())
				.tocLevels(getTocLevels())
				.invoke(
					(req1, resp1, page) -> {
						resp1.setContentType("application/xhtml+xml");
						resp1.setCharacterEncoding("UTF-8");
						method.doMethod(page);
					}
				)
			;
		} catch(SkipPageException e) {
			Includer.setPageSkipped(req);
		}
	}

	@Override
	final protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		callInPage(req, resp, this::doGet);
	}

	/**
	 * Page and the PageContext are already setup.
	 * The response content type has been set to application/xhtml+xml.
	 * The response character encoding has been set to UTF-8.
	 */
	protected void doGet(Page page) throws ServletException, IOException, SkipPageException {
		Includer.sendError(PageContext.getRequest(), PageContext.getResponse(), HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		throw new SkipPageException();
	}

	@Override
	final protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		callInPage(req, resp, this::doPost);
	}

	/**
	 * Page and the PageContext are already setup.
	 * The response content type has been set to application/xhtml+xml.
	 * The response character encoding has been set to UTF-8.
	 */
	protected void doPost(Page page) throws ServletException, IOException, SkipPageException {
		Includer.sendError(PageContext.getRequest(), PageContext.getResponse(), HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		throw new SkipPageException();
	}

	@Override
	final protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		callInPage(req, resp, this::doPut);
	}

	/**
	 * Page and the PageContext are already setup.
	 * The response content type has been set to application/xhtml+xml.
	 * The response character encoding has been set to UTF-8.
	 */
	protected void doPut(Page page) throws ServletException, IOException, SkipPageException {
		Includer.sendError(PageContext.getRequest(), PageContext.getResponse(), HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		throw new SkipPageException();
	}

	@Override
	final protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		callInPage(req, resp, this::doDelete);
	}

	/**
	 * Page and the PageContext are already setup.
	 * The response content type has been set to application/xhtml+xml.
	 * The response character encoding has been set to UTF-8.
	 */
	protected void doDelete(Page page) throws ServletException, IOException, SkipPageException {
		Includer.sendError(PageContext.getRequest(), PageContext.getResponse(), HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		throw new SkipPageException();
	}

	@Override
	final protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		callInPage(req, resp, this::doOptions);
	}

	/**
	 * Page and the PageContext are already setup.
	 * The response content type has been set to application/xhtml+xml.
	 * The response character encoding has been set to UTF-8.
	 */
	protected void doOptions(Page page) throws ServletException, IOException, SkipPageException {
		ServletUtil.doOptions(
			PageContext.getResponse(),
			PageServlet.class,
			this.getClass(),
			"doGet",
			"doPost",
			"doPut",
			"doDelete",
			new Class<?>[] {
				Page.class
			}
		);
	}
}