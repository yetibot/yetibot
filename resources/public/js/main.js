var Yeti = {
  initialize: function() {
    Yeti.show();
  },
  show: function() {
    $('.content').addClass('shown');
  }
};

$(Yeti.initialize);
