<%@ page language="java" pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/menu.jsp" %>

<h2>Liste des dossiers</h2>

<form id="rechercheForm" class="form-inline">
  <label for="recherche">Recherche</label>
  <input type="text" id="recherche" name="recherche"/>

  <label for="statut" class="mandatory">Statut</label>
  <select id="statut" name="statut">
    <option value="OUVERT">Ouvert</option>
    <option value="FERME">Fermé</option>
  </select>

  <button type="button" id="rechercherBtn">Rechercher</button>
</form>

<table id="resultats"></table>

<%@ include file="/WEB-INF/jsp/fragments/footer.jsp" %>
<script type="text/javascript" src="${pageContext.request.contextPath}/assets/liste.js"></script>
