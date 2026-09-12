const Page = {
    idRecherche: '#recherche',
    idStatut: '#statut',

    init: function () {
        const page = this;
        $('#rechercherBtn').on('click', function () {
            page.rechercher();
        });
        var detailLink = basepath + '/view/dossier/detail';
    },

    toJsonRecherche: function () {
        return {
            recherche: $(this.idRecherche).val(),
            statut: $(this.idStatut).val()
        };
    },

    rechercher: function () {
        const page = this;
        const criteres = page.toJsonRecherche();
        $.ajax({
            url: basepath + '/dossier/liste',
            method: 'GET',
            data: criteres,
            success: function (data) {
                $('#resultats').empty();
                $('#resultats').append(data.html);
            },
            error: function (e) {
                console.log(e);
            }
        });
    }
};

$(document).ready(function () {
    Page.init();
});
