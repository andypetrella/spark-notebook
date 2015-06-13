package notebook.front.third

import io.continuum.bokeh.{Glyph, Widget => BWidget, _}
import notebook._
import notebook.front._
import play.api.libs.json._

/**
 * Created by gerrit on 15.11.14.
 */
class LowLevelBokeh[A](data: Seq[A], plottingScript: String)
    (override implicit val singleCodec: Codec[JsValue, A])
  extends Playground[A](data) {

  override val scripts = Nil
  // bokeh is currently directly imported... no AMD atm
  override val snippets = List(
    """
    function(dataO, container, options, mutableContext) {
      var collection = Bokeh.Collections("ColumnDataSource").create({
        data : @dataInit
      });

      mutableContext.data = collection;

      dataO.subscribe(function(data) {
        collection.set("data", data);
        collection.trigger("change", collection, {});
      });
    }
    """,
    plottingScript
  )
}

class Bokeh(data: Seq[PlotContext])(override implicit val singleCodec: Codec[JsValue, PlotContext])
  extends Playground[PlotContext](data, List(Script("bokehWrap", JsObject(Nil))), Nil) {

}

object Bokeh {
  implicit val contextCodec: Codec[JsValue, PlotContext] = new Codec[JsValue, PlotContext] {
    val serializer = new JSONSerializer {
      val stringifyFn = Resources.default.stringify _
    }

    import serializer._

    def encode(x: JsValue): PlotContext = ???

    def decode(x: PlotContext): JsValue = Json.obj(
      "models" -> Json.toJson(serializer.collectObjs(x).map(serializer.getModel)),
      "modelType" -> x.getRef.`type`,
      "modelId" -> x.getRef.id
    )
  }

  /**
   * Renders the plots onto the display
   */
  def plot(plots: List[Plot]) = new Bokeh(Seq(new PlotContext().children(plots)))

  /**
   * Renders the plots onto the display
   */
  def widget(widgets: List[BWidget]) = new Bokeh(Seq(new PlotContext().children(widgets)))

  case class Point(x: Double, y: Double)

  def buildLine(points: Seq[Point], line_color: Color = Color.Black, line_width: Double = 2.0) = {
    val xs = points.map(_.x)
    val ys = points.map(_.y)
    val lines_source = new ColumnDataSource()
      .addColumn('x, xs)
      .addColumn('y, ys)

    val line = new Line().x('x).y('y)
      .line_color(line_color)
      .line_width(line_width)

    val line_renderer = new Glyph().data_source(lines_source).glyph(line)

    line_renderer
  }

  /**
   * Creates a plot of the image real-valued function of the given points.
   * @param xs The parameter values.
   * @param f The function to the computed (real-valued right now).
   */
  def functionGraph(xs: Seq[Double], f: Double => Double) = {
    val ys = xs map f

    val source = new ColumnDataSource()
      .addColumn('x, xs)
      .addColumn('y, ys)
    val xdr = new DataRange1d().sources(source.columns('x) :: Nil)
    val ydr = new DataRange1d().sources(source.columns('y) :: Nil)
    val plot = new Plot().x_range(xdr).y_range(ydr)

    val glyphs = new Glyph()
      .data_source(source)
      .glyph(new Circle().x('x).y('y).radius(0.01))

    val xaxis = new LinearAxis().plot(plot).location(Location.Below)
    val yaxis = new LinearAxis().plot(plot).location(Location.Left)

    plot.below <<= (xaxis :: _)
    plot.left <<= (yaxis :: _)

    plot.renderers := List(xaxis, yaxis, glyphs)
    plot
  }

  def plotFunctionGraph(xs: Seq[Double], f: Double => Double) = plot(functionGraph(xs, f) :: Nil)

  case class ScatterGroup(r: Double = 1.0, fill_color: Color = Color.Aqua,
    line_color: Color = Color.Black)

  val stdGroup = ScatterGroup()

  // Scatter plot constructor
  case class ScatterPoint(x: Double, y: Double, group: ScatterGroup = stdGroup)

  def scatter(points: Seq[ScatterPoint]) = {
    def buildPlotBase(points: Seq[ScatterPoint]) = {
      // Structure data
      val source = new ColumnDataSource()
        .addColumn('x, points.map(_.x))
        .addColumn('y, points.map(_.y))

      val xdr = new DataRange1d().sources(source.columns('x) :: Nil)
      val ydr = new DataRange1d().sources(source.columns('y) :: Nil)
      val plot = new Plot().x_range(xdr).y_range(ydr)
      plot
    }

    def buildGlyph(group: ScatterGroup, points: Seq[ScatterPoint]): Glyph = {
      val source = new ColumnDataSource()
        .addColumn('x, points.map(_.x))
        .addColumn('y, points.map(_.y))

      val circle = new Circle().x('x).y('y)
        .radius(group.r, SpatialUnits.Screen)
        .fill_color(group.fill_color)
        .line_color(group.line_color)

      val glyph = new Glyph()
        .data_source(source)
        .glyph(circle)

      glyph
    }

    val plot = buildPlotBase(points)

    val pointsByGroup = points.groupBy(_.group)
    val glyphs = pointsByGroup.map(buildGlyph _ tupled)

    val xaxis = new LinearAxis().plot(plot).location(Location.Below)
    val yaxis = new LinearAxis().plot(plot).location(Location.Left)

    plot.below <<= (xaxis :: _)
    plot.left <<= (yaxis :: _)

    plot.renderers := List(xaxis, yaxis) ++ glyphs
    plot
  }

  def scatterPlot(points: Seq[ScatterPoint]) = plot(scatter(points) :: Nil)
}

