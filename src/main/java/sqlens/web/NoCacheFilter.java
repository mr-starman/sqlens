package sqlens.web;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Makes the browser check with Tomcat before reusing a cached page, script or
 * stylesheet. Without it a browser may keep running the old UI for a while
 * after a new WAR is deployed. Unchanged files still come back as a cheap 304.
 * API responses set their own {@code no-store}, which replaces this header.
 */
@WebFilter("/*")
public class NoCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((HttpServletResponse) response).setHeader("Cache-Control", "no-cache");
        chain.doFilter(request, response);
    }
}
