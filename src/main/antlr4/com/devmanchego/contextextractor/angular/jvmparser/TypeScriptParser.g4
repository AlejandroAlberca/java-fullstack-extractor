parser grammar TypeScriptParser;

options { tokenVocab = TypeScriptLexer; }

/**
 * Island grammar: the parser simply groups the token stream into a flat sequence
 * of "atoms" (balanced-bracket groups or single tokens).
 * All pattern recognition logic lives in AntlrAngularParser.java, which
 * walks the token stream manually using the ANTLR CommonTokenStream.
 */
compilationUnit
    : atom* EOF
    ;

atom
    : LPAREN  atom* RPAREN
    | LBRACE  atom* RBRACE
    | LBRACKET atom* RBRACKET
    | LANGLE  atom* RANGLE
    | token
    ;

token
    : ID | NUMBER | STRING_DQ | STRING_SQ | TEMPLATE_STRING
    | DOT | COMMA | COLON | QUESTION | EQUALS | BANG | PIPE | AMP
    | ARROW | ELLIPSIS | OTHER | SEMICOLON
    | AT | THIS | NEW | RETURN | CONST | LET | VAR
    | CLASS | INTERFACE | CONSTRUCTOR | EXTENDS | IMPLEMENTS
    | READONLY | PRIVATE | PUBLIC | PROTECTED | FROM | IMPORT | EXPORT
    ;
