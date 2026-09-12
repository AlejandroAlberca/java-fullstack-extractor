const Menu = {
    init: function () {
        $('.link-top').on('click', function () {
            $(this).parent().toggleClass('open');
        });
    }
};

$(document).ready(function () {
    Menu.init();
});
