<%@ page contentType="text/html;charset=ISO-8859-1" language="java"
         pageEncoding="ISO-8859-1"
%><%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"
%><%@taglib prefix="mde" uri="/manydesigns-elements"
%><jsp:include page="/skins/default/header.jsp"/>
<div id="inner-content">
    <h1>Handlers</h1>
    <table>
        <tr>
            <th>Name</th>
            <th>Level</th>
        </tr>
    <s:iterator value="handlers">
        <tr>
            <td><s:property value="toString()"/></td>
            <td><s:property value="level.name"/></td>
        </tr>
    </s:iterator>
    </table>

    <h1>Loggers</h1>
    Current LogManager: <s:property value="logManager"/><br/>
    <table>
        <tr>
            <th>Name</th>
            <th>Level</th>
        </tr>
    <s:iterator value="loggers">
        <tr>
            <td><s:property value="name"/></td>
            <td><s:property value="level.name"/></td>
        </tr>
    </s:iterator>
    </table>
</div>
<jsp:include page="/skins/default/footer.jsp"/>