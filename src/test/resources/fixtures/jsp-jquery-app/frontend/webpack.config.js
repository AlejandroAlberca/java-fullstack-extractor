var path = require('path');

var config = {
    entry: {
        "liste": './src/main/js/pages/liste.js',
        "detail": './src/main/js/pages/detail.js',
        "menu": './src/main/js/structure/menu.js'
    },
    output: {
        path: path.join(__dirname, './src/main/webapp/assets/'),
        filename: '[name].js'
    },
    resolve: {
        extensions: ['.js'],
        alias: {
            jquery: "jquery/src/jquery"
        }
    }
};

module.exports = config;
