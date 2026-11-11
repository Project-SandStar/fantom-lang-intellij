class Foo
{
  Void f()
  {
    x := Foo {
      Button { it.text="Button"; it.onAction { echo("# Button-1") }},
      XElem("alpha") { XElem("beta"), },
    }
  }
}
