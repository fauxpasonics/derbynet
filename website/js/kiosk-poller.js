// Assumes ajax-failure.inc has already established a global ajax error handler

// KioskPoller.start(address, kiosk_page) starts the polling loop; see inc/kiosk-poller.inc
//
// On each cycle, KioskPoller.param_callback gets invoked with the current param
// string; parameter-aware kiosk pages should assign KioskPoller.param_callback
// to track parameter changes.

var KioskPoller = (function(KioskPoller) {

  KioskPoller.param_callback = function(param_string) {
    // console.log("Params: " + param_string);
  };
  
  KioskPoller.start = function(address, kiosk_page) {
    console.log("KioskPoller: " + address + " " + kiosk_page);
    setInterval(function() {
      $.ajax('action.php',
             {type: 'GET',
              data: {query: 'poll.kiosk',
                     address: address},
              success: function(data) {
                cancel_ajax_failure();
                $("#kiosk_name").text(data.documentElement.getAttribute("name"));
                var page = data.documentElement.getAttribute("page");
                if (page != kiosk_page) {
                  console.log("Forcing a reload, because page (" + page
                              + ") != current kiosk_page (" + kiosk_page + ")");
                  location.reload(true);
                  return;
                }
	            var reload = data.documentElement.getElementsByTagName("reload");
                if (reload && reload.length > 0) {
                  console.log("Forcing a reload because it was explicitly requested.");
                  location.reload(true);
                  return;
                }
                KioskPoller.param_callback(data.documentElement.getAttribute("params"));
              }
             });
    }, 5000);
  }

  return KioskPoller;
}(KioskPoller || {}));

