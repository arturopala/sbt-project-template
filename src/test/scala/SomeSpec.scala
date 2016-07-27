import org.scalatest.{WordSpecLike, Matchers}
import org.scalatest.prop.PropertyChecks
import org.scalatest.junit.JUnitRunner
import org.scalacheck._

class SomeSpec extends WordSpecLike with Matchers with PropertyChecks {

  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100, minSuccessful = 100, workers = 5)

  "A ???" must {

    "???" which {
      "???" in {
        forAll() { (a: Double, b: Double) =>

        }
      }
    }

  }
}