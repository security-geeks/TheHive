package services

import javax.inject.Inject
import models.AlertModel
import org.elastic4play.services.Agg
import models.Alert
import models.AlertModel
import org.elastic4play.services.GetSrv
import org.elastic4play.services.CreateSrv
import org.elastic4play.services.UpdateSrv
import org.elastic4play.services.DeleteSrv
import org.elastic4play.services.FindSrv
import org.elastic4play.services.QueryDef
import akka.stream.scaladsl.Source
import akka.NotUsed
import scala.concurrent.Future
import play.api.libs.json.JsObject
import org.elastic4play.services.AuthContext
import org.elastic4play.controllers.Fields
import scala.util.Try
import models.AlertStatus
import play.api.libs.json.Json
import org.elastic4play.services.QueryDSL
import akka.stream.scaladsl.Sink
import akka.stream.Materializer
import models.Case
import play.api.libs.json.JsNumber
import play.api.libs.json.JsArray
import play.api.libs.json.JsBoolean
import models.CaseStatus
import scala.concurrent.ExecutionContext
import scala.util.Success
import play.api.Configuration
import connectors.ConnectorRouter

trait AlertTransformer {
  def createCase(alert: Alert)(implicit authContext: AuthContext): Future[Case]
}

class AlertSrv(
    templates: Map[String, String],
    alertModel: AlertModel,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    caseSrv: CaseSrv,
    artifactSrv: ArtifactSrv,
    caseTemplateSrv: CaseTemplateSrv,
    connectors: ConnectorRouter,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) extends AlertTransformer {

  @Inject() def this(
    configuration: Configuration,
    alertModel: AlertModel,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    caseSrv: CaseSrv,
    artifactSrv: ArtifactSrv,
    caseTemplateSrv: CaseTemplateSrv,
    connectors: ConnectorRouter,
    ec: ExecutionContext,
    mat: Materializer) = this(
    Map.empty[String, String],
    alertModel: AlertModel,
    createSrv,
    getSrv,
    updateSrv,
    deleteSrv,
    findSrv,
    caseSrv,
    artifactSrv,
    caseTemplateSrv,
    connectors,
    ec,
    mat)

  def create(fields: Fields)(implicit authContext: AuthContext): Future[Alert] =
    createSrv[AlertModel, Alert](alertModel, fields)

  def bulkCreate(fieldSet: Seq[Fields])(implicit authContext: AuthContext): Future[Seq[Try[Alert]]] =
    createSrv[AlertModel, Alert](alertModel, fieldSet)

  def get(id: String): Future[Alert] =
    getSrv[AlertModel, Alert](alertModel, id)

  def get(tpe: String, source: String, sourceRef: String): Future[Option[Alert]] = {
    import QueryDSL._
    findSrv[AlertModel, Alert](alertModel, and("type" ~= tpe, "source" ~= source, "sourceRef" ~= sourceRef), Some("0-1"), Nil)
      ._1
      .runWith(Sink.headOption)
  }

  def update(id: String, fields: Fields)(implicit authContext: AuthContext): Future[Alert] =
    updateSrv[AlertModel, Alert](alertModel, id, fields)

  def bulkUpdate(ids: Seq[String], fields: Fields)(implicit authContext: AuthContext): Future[Seq[Try[Alert]]] = {
    updateSrv[AlertModel, Alert](alertModel, ids, fields)
  }

  def bulkUpdate(updates: Seq[(Alert, Fields)])(implicit authContext: AuthContext): Future[Seq[Try[Alert]]] =
    updateSrv[Alert](updates)

  def markAsRead(alert: Alert)(implicit authContext: AuthContext): Future[Alert] = {
    alert.caze() match {
      case Some(_) ⇒ updateSrv[AlertModel, Alert](alertModel, alert.id, Fields.empty.set("status", "Imported"))
      case None    ⇒ updateSrv[AlertModel, Alert](alertModel, alert.id, Fields.empty.set("status", "Ignore"))
    }
  }

  def markAsUnread(alert: Alert)(implicit authContext: AuthContext): Future[Alert] = {
    alert.caze() match {
      case Some(_) ⇒ updateSrv[AlertModel, Alert](alertModel, alert.id, Fields.empty.set("status", "Update"))
      case None    ⇒ updateSrv[AlertModel, Alert](alertModel, alert.id, Fields.empty.set("status", "New"))
    }
  }

  private def getCaseTemplate(alert: Alert) = {
    val templateName = alert.caseTemplate()
      .orElse(templates.get(alert.tpe()))
      .getOrElse(alert.tpe())
    caseTemplateSrv.getByName(templateName)
      .map { ct ⇒ Some(ct) }
      .recover { case _ ⇒ None }
  }

  def createCase(alert: Alert)(implicit authContext: AuthContext): Future[Case] = {
    alert.caze() match {
      case Some(id) ⇒ caseSrv.get(id)
      case None ⇒
        val caze = connectors.get(alert.tpe()) match {
          case Some(connector: AlertTransformer) ⇒ connector.createCase(alert)
          case _ ⇒
            getCaseTemplate(alert).flatMap { caseTemplate ⇒
              caseSrv.create(Fields.empty
                .set("title", (caseTemplate.flatMap(_.titlePrefix()).getOrElse("") + s" #${alert.sourceRef()} " + alert.title()).trim)
                .set("description", alert.description())
                .set("severity", JsNumber(alert.severity()))
                .set("tags", Json.arr(alert.tags()))
                .set("tlp", JsNumber(alert.tlp()))
                .set("status", CaseStatus.Open.toString))
                .andThen {
                  case Success(caze) ⇒ artifactSrv.create(caze, alert.artifacts().map(Fields.apply))
                }
            }
        }
        caze.flatMap { c ⇒
          updateSrv[AlertModel, Alert](alertModel, alert.id, Fields.empty.set("case", c.id))
            .map(_ ⇒ c)
        }
    }
  }

  def delete(id: String)(implicit Context: AuthContext): Future[Alert] =
    deleteSrv[AlertModel, Alert](alertModel, id)

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Alert, NotUsed], Future[Long]) = {
    findSrv[AlertModel, Alert](alertModel, queryDef, range, sortBy)
  }

  def stats(queryDef: QueryDef, aggs: Seq[Agg]): Future[JsObject] = findSrv(alertModel, queryDef, aggs: _*)

  def ignoreAlert(alertId: String)(implicit authContext: AuthContext) = {
    updateSrv[AlertModel, Alert](alertModel, alertId, Fields(Json.obj("status" → AlertStatus.Ignore)))
  }

  def setFollowAlert(alertId: String, follow: Boolean)(implicit authContext: AuthContext) = {
    updateSrv[AlertModel, Alert](alertModel, alertId, Fields(Json.obj("follow" → follow)))
  }

  def setCaseId(alertId: String, caseId: String)(implicit authContext: AuthContext) = {
    updateSrv[AlertModel, Alert](alertModel, alertId, Fields(Json.obj("case" → caseId, "status" → AlertStatus.Imported)))
  }
}