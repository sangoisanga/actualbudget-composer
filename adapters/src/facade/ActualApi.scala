package composer.adapters.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@actual-app/api", JSImport.Namespace)
object ActualApi extends js.Object {
  def init(config: js.Object): js.Promise[js.Any] = js.native
  def downloadBudget(syncId: String): js.Promise[Unit] = js.native
  def getCategories(): js.Promise[js.Array[NamedEntity]] = js.native
  def getAccounts(): js.Promise[js.Array[NamedEntity]] = js.native
  def importTransactions(accountId: String, transactions: js.Array[js.Object]): js.Promise[ImportResponse] = js.native
  def shutdown(): js.Promise[Unit] = js.native
}

@js.native
trait NamedEntity extends js.Object {
  val id: String = js.native
  val name: String = js.native
}

@js.native
trait ImportResponse extends js.Object {
  val errors: js.UndefOr[js.Array[ImportError]] = js.native
  val updatedPreview: js.UndefOr[js.Array[PreviewEntry]] = js.native
}

@js.native
trait ImportError extends js.Object {
  val message: String = js.native
}

@js.native
trait PreviewEntry extends js.Object {
  val transaction: PreviewTransaction = js.native
  val existing: js.UndefOr[js.Object] = js.native
  val ignored: js.UndefOr[Boolean] = js.native
}

@js.native
trait PreviewTransaction extends js.Object {
  val imported_id: js.UndefOr[String] = js.native
}
