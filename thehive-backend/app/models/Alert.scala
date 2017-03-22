package models

import java.util.Date

import org.elastic4play.models.{ AttributeDef, HiveEnumeration, AttributeFormat ⇒ F, AttributeOption ⇒ O }
import models.JsonFormat.alertStatusFormat
import javax.inject.Inject

import org.elastic4play.models.ModelDef
import services.AuditedModel
import org.elastic4play.models.EntityDef
import play.api.Logger
import play.api.libs.json.Json
import javax.inject.Singleton

import play.api.libs.json.JsObject
import org.elastic4play.models.Attribute

object AlertStatus extends Enumeration with HiveEnumeration {
  type Type = Value
  val New, Update, Ignore, Imported = Value
}

trait AlertAttributes { _: AttributeDef ⇒
  def artifactAttributes: Seq[Attribute[_]]

  val tpe = attribute("type", F.stringFmt, "Type of the alert", O.readonly)
  val source = attribute("source", F.stringFmt, "Source of the alert", O.readonly)
  val sourceRef = attribute("sourceRef", F.stringFmt, "Source reference of the alert", O.readonly)
  val date = attribute("date", F.dateFmt, "Date of the alert", O.readonly)
  val lastSyncDate = attribute("lastSyncDate", F.dateFmt, "Date of the last synchronization")
  val caze = optionalAttribute("case", F.stringFmt, "Id of the case, if created")
  val title = attribute("title", F.textFmt, "Title of the alert")
  val description = attribute("description", F.textFmt, "Description of the alert")
  val severity = attribute("severity", F.numberFmt, "Severity if the alert (0-5)", 3L)
  val tags = multiAttribute("tags", F.stringFmt, "Alert tags")
  val tlp = attribute("tlp", F.numberFmt, "TLP level", 2L)
  val artifacts = multiAttribute("artifacts", F.objectFmt(artifactAttributes), "Artifact of the alert")
  val caseTemplate = optionalAttribute("caseTemplate", F.stringFmt, "Case template to use")
  val status = attribute("status", F.enumFmt(AlertStatus), "Status of the alert", AlertStatus.New)
  val follow = attribute("follow", F.booleanFmt, "", true)
}

@Singleton
class AlertModel @Inject() (artifactModel: ArtifactModel) extends ModelDef[AlertModel, Alert]("alert") with AlertAttributes with AuditedModel { alertModel ⇒
  lazy val logger = Logger(getClass)
  override val defaultSortBy = Seq("-date")
  override val removeAttribute = Json.obj("status" → AlertStatus.Ignore)
  def artifactAttributes = artifactModel.attributes
}

class Alert(model: AlertModel, attributes: JsObject) extends EntityDef[AlertModel, Alert](model, attributes) with AlertAttributes {
  def artifactAttributes = Nil
  def toCaseJson = Json.obj(
    //"caseId" -> caseId,
    "title" → title(),
    "description" → description(),
    "severity" → severity(),
    //"owner" -> owner,
    "startDate" → date(),
    "tags" → tags(),
    "tlp" → tlp(),
    "status" → CaseStatus.Open)
}
