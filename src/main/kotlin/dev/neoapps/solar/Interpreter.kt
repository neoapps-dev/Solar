package dev.neoapps.solar

import dev.neoapps.solar.SolarParser.*
import kotlin.system.exitProcess

class Interpreter : SolarBaseListener() {
    private val functions = mutableMapOf<String, Function>()
    private var returnValue: Any? = null
    private var isReturning = false

    init {
        functions["println"] = Function(
            name = "println",
            params = arrayOf(),
            unlimitedParams = true,
            body = { args ->
                if (args.size == 1) {
                    println(args[0])
                } else {
                    println(args)
                }
            }
        )

        functions["exit"] = Function(
            name = "exit",
            params = arrayOf("exitCode"),
            body = { args ->
                val exitCode = args.getOrNull(0) ?: -1
                if (exitCode is Int) {
                    exitProcess(exitCode)
                } else {
                    exitProcess(-1)
                }
            }
        )

        functions["printStackTrace"] = Function(
            name = "printStackTrace",
            params = arrayOf(),
            body = {
                Stack.printStackTrace()
            }
        )

        functions["printVariableTrace"] = Function(
            name = "printVariableTrace",
            params = arrayOf(),
            body = {
                Stack.printVariableStack()
            }
        )
    }

    override fun exitAssignment(ctx: AssignmentContext) {
        if (isAFunction || isReturning) {
            return
        }
        val variable: String = ctx.IDENTIFIER()?.text ?: throw RuntimeException("Missing identifier")
        val value: Any = evaluateExpression(ctx.expression())

        Stack.defineVariable(variable, value)
    }

    private fun evaluateExpression(expr: ExpressionContext): Any {
        val left = evaluateTerm(expr.term(0))
        var result = left

        for (i in 1 until expr.term().size) {
            val operator = expr.getChild(2 * i - 1).text
            val right = evaluateTerm(expr.term(i))

            result = when (operator) {
                "+" -> {
                    if (result is Int && right is Int) {
                        result + right
                    } else {
                        result.toString() + right.toString()
                    }
                }
                "-" -> {
                    if (result is Int && right is Int) {
                        result - right
                    } else {
                        throw RuntimeException("Subtraction is not supported for non-integer types")
                    }
                }
                else -> throw RuntimeException("Unknown operator: $operator")
            }
        }

        if (expr.text == "true") return true
        if (expr.text == "false") return false

        return result
    }

    var isAFunction = false
    override fun enterFunctionDecl(ctx: FunctionDeclContext?) {
        if (isAFunction) {
            throw RuntimeException("Cannot define functions in this scope")
        }
        isAFunction = true
        val name = ctx!!.IDENTIFIER().text!!
        val params = ctx.parameterList()?.IDENTIFIER()
        val body = ctx.statement()

        if (functions.containsKey(name)) {
            throw RuntimeException("Function with name $name is already defined")
        }

        functions[name] = Function(
            name = name,
            params = params?.map { it.text }?.toTypedArray() ?: emptyArray(),
            body = {
                eval(body)
                val returnValueCopy = returnValue
                returnValue = null
                isReturning = false
                returnValueCopy
            }
        )
    }

    fun eval(body: List<StatementContext>) {
        body.forEach { statement ->
            if (isReturning) return
            when (statement) {
                is FunctionCallStmtContext -> {
                    exitFunctionCall(statement.functionCall())
                }
                is FunctionDeclStmtContext -> {
                    enterFunctionDecl(statement.functionDecl())
                }
                is AssignmentStmtContext -> {
                    exitAssignment(statement.assignment())
                }
                is ReturnAStmtContext -> {
                    exitReturnStmt(statement.returnStmt())
                }
            }
        }
    }

    override fun exitFunctionDecl(ctx: FunctionDeclContext) {
        isAFunction = false
    }

    override fun exitFunctionCall(ctx: FunctionCallContext?) {
        if (isAFunction || isReturning) {
            return
        }
        val name = ctx?.IDENTIFIER()?.text!!
        val argList = ctx?.argumentList()
        val args = mutableListOf<Argument>()
        if (argList?.isEmpty?.not() == true) {
            val rawArgs = ctx.argumentList().expression()
            if (rawArgs.isNotEmpty()) {
                for (i in 0 until rawArgs.size) {
                    val arg = evaluateExpression(rawArgs[i])
                    args.add(arg)
                }
            }
        }

        val function = functions[name] ?: throw RuntimeException("Undefined function $name")
        function.invoke(args = args.toTypedArray())
    }

    private fun evaluateTerm(term: TermContext): Any {
        val left = evaluateFactor(term.factor(0))
        var result = left

        for (i in 1 until term.factor().size) {
            val operator = term.getChild(2 * i - 1).text
            val right = evaluateFactor(term.factor(i))

            result = when (operator) {
                "*" -> (result as Int) * (right as Int)
                "/" -> (result as Int) / (right as Int)
                else -> throw RuntimeException("Unknown operator: $operator")
            }
        }
        return result
    }

    private fun evaluateFactor(factor: FactorContext): Any {
        return when {
            factor.INT() != null -> factor.INT().text.toInt()
            factor.IDENTIFIER() != null -> {
                val variable = factor.IDENTIFIER().text
                return Stack.getVariable(variable)
            }
            factor.STRING() != null -> {
                val rawString = factor.STRING().text
                processString(rawString)
            }
            factor.expression() != null -> {
                evaluateExpression(factor.expression())
            }
            else -> throw RuntimeException("Invalid factor")
        }
    }

    private fun processString(rawString: String): String {
        val withoutQuotes = rawString.substring(1, rawString.length - 1)
        return withoutQuotes.replace("""\\n""", "\n")
            .replace("""\\t""", "\t")
            .replace("""\\r""", "\r")
            .replace("""\\"""", "\"")
            .replace("""\\\\""", "\\")
    }

    private fun evaluateBooleanExpression(ctx: BooleanExpressionContext): Any {
        return when {
            ctx.expression().size == 2 -> {
                val left = evaluateExpression(ctx.expression(0))
                val right = evaluateExpression(ctx.expression(1))
                val operator = ctx.getChild(1).text

                when (operator) {
                    "==" -> left == right
                    "!=" -> left != right
                    "<" -> (left as Int) < (right as Int)
                    ">" -> (left as Int) > (right as Int)
                    "<=" -> (left as Int) <= (right as Int)
                    ">=" -> (left as Int) >= (right as Int)
                    else -> throw RuntimeException("Unknown operator: $operator")
                }
            }
            ctx.text == "true" -> true
            ctx.text == "false" -> false
            ctx.booleanExpression() != null -> evaluateBooleanExpression(ctx.booleanExpression())
            else -> throw RuntimeException("Invalid boolean expression")
        }
    }

    override fun exitIfElse(ctx: SolarParser.IfElseContext) {
        if (isReturning) return
        val condition = evaluateBooleanExpression(ctx.booleanExpression())
        if (condition is Boolean && condition) {
            eval(ctx.statement(0).let { listOf(it) })
        } else if (ctx.statement().size > 1) {
            eval(ctx.statement(1).let { listOf(it) })
        }
    }

    override fun exitWhileLoop(ctx: SolarParser.WhileLoopContext) {
        while (true) {
            if (isReturning) return
            val condition = evaluateBooleanExpression(ctx.booleanExpression()) // Use evaluateBooleanExpression
            if (condition is Boolean && condition) {
                eval(ctx.statement())
            } else {
                break
            }
        }
    }

    override fun exitReturnStmt(ctx: ReturnStmtContext) {
        if (!isAFunction) {
            throw RuntimeException("Return statement outside of a function")
        }
        returnValue = ctx.expression()?.let { 
            when {
                it is FactorContext && it.IDENTIFIER() != null -> {
                    Stack.getVariable(it.IDENTIFIER().text)
                }
                else -> {
                    evaluateExpression(it)
                }
            }
        }
        isReturning = true
    }
}
