<?php session_start(); ?>
<?php
require_once('inc/data.inc');
require_once('inc/kiosks.inc');

// 'page' query argument to support testing
if (isset($_GET['page'])) {
  define('KIOSK_PARAM', '');
  require($_GET['page']);
} else {
  $kpage = kiosk_page(address_for_current_kiosk());
  // For kiosk pages that use parameters:
  define('KIOSK_PARAM', $kpage['params']);
  require($kpage['page']);
}
?>