package com.breakdowngrid.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GridConnectionTest {

    @Test
    public void safeAccessorsTrimAndHandleNull() {
        GridConnection c = new GridConnection();
        assertEquals("", c.keySafe());
        assertEquals("", c.baseUrlSafe());
        assertEquals("", c.usernameSafe());
        assertEquals("", c.passwordSafe());

        c.key = "  erp ";
        c.baseUrl = "  https://host/base/ ";
        assertEquals("erp", c.keySafe());
        assertEquals("https://host/base/", c.baseUrlSafe());
    }

    @Test
    public void authTypeDefaultsToBasic() {
        GridConnection c = new GridConnection();
        assertEquals("basic", c.authTypeSafe());   // null → basic
        c.authType = "   ";
        assertEquals("basic", c.authTypeSafe());   // blank → basic
        c.authType = "oauth";
        assertEquals("oauth", c.authTypeSafe());
    }

    @Test
    public void hasBaseUrl() {
        GridConnection c = new GridConnection();
        assertFalse(c.hasBaseUrl());
        c.baseUrl = "   ";
        assertFalse(c.hasBaseUrl());
        c.baseUrl = "https://host/";
        assertTrue(c.hasBaseUrl());
    }

    @Test
    public void passwordNotTrimmed() {
        GridConnection c = new GridConnection();
        c.password = " s3cret ";
        // password kept verbatim (spaces can be significant)
        assertEquals(" s3cret ", c.passwordSafe());
    }
}
