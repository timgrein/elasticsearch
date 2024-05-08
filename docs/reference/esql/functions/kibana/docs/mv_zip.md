<!--
This is generated by ESQL's AbstractFunctionTestCase. Do no edit it. See ../README.md for how to regenerate it.
-->

### MV_ZIP
Combines the values from two multivalued fields with a delimiter that joins them together.

```
ROW a = ["x", "y", "z"], b = ["1", "2"]
| EVAL c = mv_zip(a, b, "-")
| KEEP a, b, c
```