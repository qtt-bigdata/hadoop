package org.apache.hadoop.http;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A servlet filter to disable JSP.
 */
public class DisableJspFilter implements Filter {
  static final Logger LOG = LoggerFactory.getLogger(DisableJspFilter.class);

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
  }

  @Override
  public void doFilter(ServletRequest servletRequest,
                       ServletResponse servletResponse,
                       FilterChain filterChain)
      throws IOException, ServletException {
    final String msg = "JSP web UI has been disabled.";
    LOG.warn(msg + " Rejecting request " + servletRequest);
    ((HttpServletResponse)servletResponse).sendError(
        HttpServletResponse.SC_GONE, msg);
  }

  @Override
  public void destroy() {
  }
}
