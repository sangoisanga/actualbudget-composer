package composer.domain

import upickle.default.{ReadWriter, read}
import upickle.implicits.key

final case class PayeeMapping(@key("match") matches: List[String], payee: String, note: Option[String] = None, category: Option[String] = None) derives ReadWriter

object PayeeMap {
  def decodeMappings(json: String): List[PayeeMapping] = read[List[PayeeMapping]](json)

  // Raw payees carry gateway prefixes and codes ("MPGS BEGROUP 9 HO CHI MINH VN"). The first matching entry wins.
  def apply(mappings: List[PayeeMapping], email: ParsedEmail): ParsedEmail = {
    val raw = email.payee.toLowerCase
    mappings.find(_.matches.exists(pattern => raw.contains(pattern.toLowerCase))) match {
      case None          => email
      case Some(mapping) =>
        email.copy(payee = mapping.payee, notes = mapping.note.orElse(email.notes), categoryName = mapping.category.orElse(email.categoryName))
    }
  }
}
