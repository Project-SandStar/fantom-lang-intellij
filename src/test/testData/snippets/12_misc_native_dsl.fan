using [java] fanx.interop::Interop
using [java] java.awt.event
facet class Opt { const Str help := "" }
mixin M { abstract Int x() }
class Foo
{
  native Int foo()
  once Str bar() { "a" }
  Void f()
  {
    r := Regex<|a+b|>
    s := Str <|hello
       world|>
    x := "$<a>" + """triple "quoted" """ + '\n' + `\u1234 '\\`
    n := 0b1011 + 0xCAFE_BABE + 3e6f + 4d + -0.5hr + 1_2.3_7e-5_6d
    c := '\u{1F60A}'
    t := x is Str ? 1 : 2
    u := x as Str
    v := (Int)y
  }
}
