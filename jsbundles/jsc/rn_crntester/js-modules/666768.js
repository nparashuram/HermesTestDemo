__d(function(g,r,i,a,m,e,d){'use strict';var n=(function(n){function t(){var n,s;r(d[1])(this,t);for(var o=arguments.length,c=new Array(o),l=0;l<o;l++)c[l]=arguments[l];return(s=r(d[2])(this,(n=r(d[3])(t)).call.apply(n,[this].concat(c)))).state={dims:r(d[4]).Dimensions.get(s.props.dim)},s._handleDimensionsChange=function(n){s.setState({dims:n[s.props.dim]})},s}return r(d[0])(t,n),r(d[5])(t,[{key:"componentDidMount",value:function(){r(d[4]).Dimensions.addEventListener('change',this._handleDimensionsChange)}},{key:"componentWillUnmount",value:function(){r(d[4]).Dimensions.removeEventListener('change',this._handleDimensionsChange)}},{key:"render",value:function(){return r(d[6]).createElement(r(d[4]).View,null,r(d[6]).createElement(r(d[4]).Text,null,JSON.stringify(this.state.dims)))}}]),t})(r(d[6]).Component);e.title='Dimensions',e.description='Dimensions of the viewport',e.examples=[{title:'window',render:function(){return r(d[6]).createElement(n,{dim:"window"})}},{title:'screen',render:function(){return r(d[6]).createElement(n,{dim:"screen"})}}]},666768,[614,616,617,620,516,621,514]);