package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageCatalogTest {
  @Test
  void pathAndCodeUseOneServerSideMapping() {
    PageCatalog catalog = new PageCatalog();
    assertEquals("B_PAGE_01", catalog.requireByPath("/pages/orders").getCode());
    assertEquals("/pages/orders", catalog.requireByCode("B_PAGE_01").getPath());
    assertThrows(IllegalArgumentException.class, () -> catalog.requireByPath("https://evil.test"));
  }
}
