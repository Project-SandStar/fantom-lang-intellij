package co.anbora.labs.fantom.lang.axon;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static co.anbora.labs.fantom.lang.axon.AxonTokenTypes.*;

// Axon lexer. The token rules follow haxall axon::Tokenizer (src/core/axon/fan/parser/Tokenizer.fan).
// Every construct is one token, so the lexer can restart at any token start in YYINITIAL.

%%

%{
  private int commentDepth = 0;
  private int commentStart = -1;

  public _AxonLexer() {
    this(null);
  }
%}

%public
%class _AxonLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

%state BLOCK_COMMENT

NL = \r|\n|\r\n
WS = [ \t\f\u00A0]+

// Ref.isIdChar: letters, digits, _ : - . ~
ID_CHAR = [a-zA-Z0-9_:\-.~]

LOWER_ID = [a-z][a-zA-Z0-9_]*
UPPER_ID = [A-Z][a-zA-Z0-9_]*

// Number, date, time, month and number with unit, as in Tokenizer.num:
// digits, `_` separators, `-` (dates), `:` before a digit (times), `.` before a digit,
// an exponent sign, and unit chars (letters, %, $, _, non-ASCII, `/` not before `/`).
HEX = 0x[0-9a-fA-F_]+
NUM_PART = [0-9] | "_" | "-" | ":"[0-9] | "."[0-9] | [eE][+\-] | [a-zA-Z%$\u0080-\uFFFF] | "/"[a-zA-Z0-9%$_]
NUMBER = [0-9] {NUM_PART}*

ESC = \\[^\r\n]
STRING = \" ([^\"\\\r\n] | {ESC})* \"?
TRIPLE_STRING = \"\"\" ([^\"\\] | {ESC} | \"[^\"] | \"\"[^\"])* (\"\"\")?
RAW_STRING = r\" [^\"\r\n]* \"?
URI = ` ([^`\\\r\n] | {ESC})* `?
REF = "@" {ID_CHAR}+
SYMBOL = "^" {ID_CHAR}+

%%

<YYINITIAL> {
  {WS}                 { return WHITE_SPACE; }
  {NL}                 { return WHITE_SPACE; }

  "//" [^\r\n]*        { return LINE_COMMENT; }
  "/*"                 { commentDepth = 1; commentStart = zzStartRead; yybegin(BLOCK_COMMENT); }

  "and"                { return KEYWORD; }
  "catch"              { return KEYWORD; }
  "defcomp"            { return KEYWORD; }
  "deflinks"           { return KEYWORD; }
  "do"                 { return KEYWORD; }
  "else"               { return KEYWORD; }
  "end"                { return KEYWORD; }
  "if"                 { return KEYWORD; }
  "not"                { return KEYWORD; }
  "or"                 { return KEYWORD; }
  "return"             { return KEYWORD; }
  "throw"              { return KEYWORD; }
  "try"                { return KEYWORD; }
  "true"               { return KEYWORD; }
  "false"              { return KEYWORD; }
  "null"               { return KEYWORD; }

  {RAW_STRING}         { return STRING; }
  {TRIPLE_STRING}      { return STRING; }
  {STRING}             { return STRING; }
  {URI}                { return URI; }
  {REF}                { return REF; }
  {SYMBOL}             { return SYMBOL; }
  {HEX}                { return NUMBER; }
  {NUMBER}             { return NUMBER; }

  {UPPER_ID}           { return TYPENAME; }
  {LOWER_ID}           { return IDENTIFIER; }

  "=>"                 { return FN_ARROW; }
  "("                  { return LPAREN; }
  ")"                  { return RPAREN; }
  "["                  { return LBRACKET; }
  "]"                  { return RBRACKET; }
  "{"                  { return LBRACE; }
  "}"                  { return RBRACE; }
  ","                  { return COMMA; }
  ";"                  { return SEMICOLON; }
  "."                  { return DOT; }
  "::"                 { return OPERATOR; }
  ":"                  { return COLON; }
  "->"                 { return OPERATOR; }
  ".."                 { return OPERATOR; }
  "<=>"                { return OPERATOR; }
  "=="                 { return OPERATOR; }
  "!="                 { return OPERATOR; }
  "<="                 { return OPERATOR; }
  ">="                 { return OPERATOR; }
  [+\-*/!\^?&=<>|_]    { return OPERATOR; }

  [^]                  { return BAD_CHARACTER; }
}

<BLOCK_COMMENT> {
  "/*"                 { commentDepth++; }
  "*/"                 { if (--commentDepth == 0) { yybegin(YYINITIAL); zzStartRead = commentStart; return BLOCK_COMMENT; } }
  <<EOF>>              { yybegin(YYINITIAL); zzStartRead = commentStart; return BLOCK_COMMENT; }
  [^]                  { }
}
