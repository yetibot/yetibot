var Yeti = {
  initialize: function() {
    Yeti.show();
  },
  show: function() {
    setTimeout(function() {
      $('.content').addClass('shown');
    }, 200);
  }
};

$(Yeti.initialize);
