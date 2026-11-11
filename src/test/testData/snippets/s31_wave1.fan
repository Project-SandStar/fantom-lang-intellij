class W1
{
  Str facet := "x"
  Int enum := 1
  static Void main()
  {
    x := 0b1010_1
    z := 1_000.5f
    d := .5f
    e := 1e3f
    dur := 1.5sec
    dec := 1.0d
    obj := W1()
    r := obj->foo
    s := obj?->foo
    /* outer /* inner */ still comment */
    f := |Int i->Str| { return i.toStr }
    list.each |Int i| { echo(it) }
  }
  Str foo() { "foo" }
}
enum class Color { red, green, blue }
facet class MyFacet {}
