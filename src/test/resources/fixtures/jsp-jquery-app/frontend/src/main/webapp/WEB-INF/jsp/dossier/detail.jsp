<%@ page language="java" pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/menu.jsp" %>

<h2>Détail du dossier</h2>

<label for="libelle">Libellé</label>
<input type="text" id="libelle"/>

<label for="commentaire">Commentaire</label>
<textarea id="commentaire"></textarea>

<div id="historique"></div>

<button type="button" id="save">Enregistrer</button>

<%@ include file="/WEB-INF/jsp/fragments/footer.jsp" %>
<script type="text/javascript" src="${pageContext.request.contextPath}/assets/detail.js"></script>
