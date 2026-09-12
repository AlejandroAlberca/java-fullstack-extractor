const Detail = {
    id: '42',

    toJson: function () {
        return {
            libelle: $('#libelle').val(),
            commentaire: $('#commentaire').val()
        };
    },

    chargerHistorique: function () {
        $.ajax({
            url: basepath + '/dossier/' + Detail.id + '/historique',
            method: 'GET',
            success: function (data) {
                $('#historique').empty();
                $('#historique').append(data.html);
            }
        });
    },

    enregistrer: function () {
        const page = Detail;
        const dossier = page.toJson();
        $.ajax({
            url: basepath + '/dossier/' + Detail.id,
            method: 'PUT',
            data: JSON.stringify(dossier),
            success: function () {
                page.chargerHistorique();
            },
            error: function (e) {
                console.log(e);
            }
        });
    },

    init: function () {
        $('#save').on('click', function () {
            Detail.enregistrer();
        });
        Detail.chargerHistorique();
    }
};

$(document).ready(function () {
    Detail.init();
});
